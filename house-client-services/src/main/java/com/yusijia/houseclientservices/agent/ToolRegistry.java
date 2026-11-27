package com.yusijia.houseclientservices.agent;

import com.yusijia.houseclientservices.domain.audit.ToolCallAudit;
import com.yusijia.houseclientservices.tool.BaiduMapToolGuard;
import com.yusijia.houseclientservices.tool.ExternalContentGuard;
import com.yusijia.houseclientservices.tool.ProjectTools;
import com.yusijia.houseclientservices.tool.SessionStateTools;
import com.yusijia.houseclientservices.tool.UserProfileTools;
import com.yusijia.houseclientservices.tool.SubscriptionTools;
import com.yusijia.houseclientservices.tool.ToolExecutionContext;
import com.yusijia.houseclientservices.tool.WebSearchTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具目录：按技能提供工具子集。
 * <p>
 * 业务工具只有 9 个（含联网检索），按技能裁剪是为了控制注意力；MCP 的 14 个工具里大部分永远用不到，
 * 白名单直接由 SKILL.md 的 tools 推导，在注册期裁掉未声明的部分。
 * 按技能裁剪后，单次请求只挂 2~4 个工具，避免 schema 烧 token、分散模型注意力。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolCallback> byName = new LinkedHashMap<>();

    /** 会改变业务数据的工具：读过外部不可信内容后本轮直接熔断 */
    private static final Set<String> WRITE_TOOLS = Set.of("createSubscription", "cancelSubscription");

    public ToolRegistry(SubscriptionTools subscriptionTools,
                        ProjectTools projectTools,
                        WebSearchTools webSearchTools,
                        SessionStateTools sessionStateTools,
                        UserProfileTools userProfileTools,
                        ToolCallbackProvider[] mcpToolProviders,
                        SkillCatalog skillCatalog) {
        // MCP 白名单由技能声明推导：SKILL.md 的 tools 里写了哪些，就注册哪些。
        // 百度地图 MCP 一次暴露 14 个工具（含路况、天气、行政区、POI 详情等），
        // 本系统只用得到其中几个，其余 schema 每次请求都在烧 token。
        Set<String> declaredTools = new LinkedHashSet<>();
        skillCatalog.all().forEach(skill -> declaredTools.addAll(skill.tools()));
        Set<String> dropped = new LinkedHashSet<>();
        // 本地 @Tool 方法
        ToolCallback[] businessCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(subscriptionTools, projectTools, webSearchTools, sessionStateTools, userProfileTools)
                .build()
                .getToolCallbacks();
        for (ToolCallback callback : businessCallbacks) {
            String name = callback.getToolDefinition().name();
            // 写工具统一套"外部内容熔断"：读过网页正文后，本轮不允许落库操作
            ToolCallback secured = WRITE_TOOLS.contains(name) ? guardWriteTool(callback) : callback;
            // 审计装饰器放最外层：熔断/守卫的拒绝文案同样是一次工具调用，也要进审计
            byName.put(name, audit(secured));
        }

        // 百度地图 MCP 工具：统一包装，剥掉 MCP content 数组、格式化输出、记住地理编码坐标
        // 同时做静态收敛：只保留白名单中的工具，其余直接丢弃
        if (mcpToolProviders != null) {
            for (ToolCallbackProvider provider : mcpToolProviders) {
                if (provider == null || provider.getToolCallbacks() == null) {
                    continue;
                }
                for (ToolCallback callback : provider.getToolCallbacks()) {
                    String name = callback.getToolDefinition().name();
                    if (declaredTools.contains(name)) {
                        byName.put(name, audit(wrap(callback)));
                    } else {
                        dropped.add(name);
                    }
                }
            }
        }
        if (!dropped.isEmpty()) {
            log.info("MCP 工具未在任何 SKILL.md 的 tools 中声明，已裁剪: {}", dropped);
        }

        // 技能里写错工具名（拼写错误）在这里就能发现，不用等模型调用时才发现工具不存在
        for (Skill skill : skillCatalog.all()) {
            for (String tool : skill.tools()) {
                if (!byName.containsKey(tool)) {
                    log.warn("技能 {} 声明的工具未注册: {}", skill.name(), tool);
                }
            }
        }
    }

    /**
     * 按技能集合取工具。按 skills 顺序取各自 tools() 并集（LinkedHashSet 去重），
     * 按名字从 byName 取；某个名字查不到则 log.warn 跳过。
     * <p>
     * <b>空技能列表 = 空工具列表</b>：调用方只有在"模型明确判定本轮无技能"
     * （纯寒暄/道谢）时才会传空，这时下发任何业务工具都是浪费 schema token，
     * 还会让模型在寒暄轮里尝试调工具。选择器自身异常时返回的是全量技能，不是空列表。
     *
     * @param skills 选中的技能列表
     * @return 工具回调列表，可能为空
     */
    public List<ToolCallback> forSkills(List<Skill> skills) {
        if (skills == null || skills.isEmpty()) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (Skill skill : skills) {
            names.addAll(skill.tools());
        }
        List<ToolCallback> selected = new ArrayList<>(names.size());
        for (String name : names) {
            ToolCallback callback = byName.get(name);
            if (callback != null) {
                selected.add(callback);
            } else {
                log.warn("工具未注册: {}（可能该工具已被静态裁剪）", name);
            }
        }
        return selected;
    }

    /** 全部已注册工具名，便于排查 */
    public Set<String> registeredToolNames() {
        return Set.copyOf(byName.keySet());
    }

    /**
     * 给写工具套一层熔断：本轮读过外部资料时把拒绝文案当作工具返回值，
     * 不抛异常——异常会让客户拿到一个报错，而拒绝文案还能被模型转述成"请您再确认"。
     * <p>
     * 放行判断在每次调用时做，所以同一份注册表可以跨轮复用。
     */
    static ToolCallback guardWriteTool(ToolCallback callback) {
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return callback.getToolDefinition();
            }

            @Override
            public String call(String toolInput) {
                return call(toolInput, null);
            }

            @Override
            public String call(String toolInput,
                               org.springframework.ai.chat.model.ToolContext toolContext) {
                // 必须取本轮上下文判断熔断，不能依赖 ThreadLocal（流式/并行下会换线程）
                String refusal = ExternalContentGuard.blockIfSeen(
                        ToolExecutionContext.of(toolContext), callback.getToolDefinition().name());
                if (refusal != null) {
                    return refusal;
                }
                // 转发上下文：内层 MethodToolCallback 需要它注入 @Tool 的 ToolContext 参数
                return callback.call(toolInput, toolContext);
            }
        };
    }

    /**
     * P0-5 审计装饰器（套在最外层）：计时 + 把每次调用记入本轮 {@link ToolExecutionContext}。
     * <p>
     * 只记录，不拦截：任何异常原样上抛（主流程该报错就报错），审计记录里标 failed；
     * 上下文缺失（框架直调、无 toolContext）时跳过记录，不影响调用本身。
     */
    static ToolCallback audit(ToolCallback callback) {
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return callback.getToolDefinition();
            }

            @Override
            public String call(String toolInput) {
                return call(toolInput, null);
            }

            @Override
            public String call(String toolInput,
                               org.springframework.ai.chat.model.ToolContext toolContext) {
                ToolExecutionContext exec = ToolExecutionContext.of(toolContext);
                long start = System.currentTimeMillis();
                try {
                    String result = callback.call(toolInput, toolContext);
                    if (exec != null) {
                        exec.recordToolCall(new ToolCallAudit(
                                callback.getToolDefinition().name(), toolInput, result,
                                System.currentTimeMillis() - start, false));
                    }
                    return result;
                } catch (RuntimeException e) {
                    if (exec != null) {
                        exec.recordToolCall(new ToolCallAudit(
                                callback.getToolDefinition().name(), toolInput,
                                "工具调用异常: " + e.getMessage(),
                                System.currentTimeMillis() - start, true));
                    }
                    throw e;
                }
            }
        };
    }

    private ToolCallback wrap(ToolCallback callback) {
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return callback.getToolDefinition();
            }

            @Override
            public String call(String toolInput) {
                return call(toolInput, null);
            }

            @Override
            public String call(String toolInput,
                               org.springframework.ai.chat.model.ToolContext toolContext) {
                ToolExecutionContext exec = ToolExecutionContext.of(toolContext);
                String name = callback.getToolDefinition().name();
                String actualInput = toolInput;
                if ("map_directions".equals(name)) {
                    String guardMessage = BaiduMapToolGuard.directionsGuard(toolInput);
                    if (guardMessage != null) {
                        return guardMessage;
                    }
                    return BaiduMapToolGuard.formatDirectionsOutput(
                            BaiduMapToolGuard.unwrapMcpContent(callback.call(actualInput, toolContext)));
                }
                if ("map_search_places".equals(name)) {
                    actualInput = BaiduMapToolGuard.rewriteSearchPlacesInput(exec, toolInput);
                    List<String> queries = BaiduMapToolGuard.splitQueries(actualInput);
                    if (queries.size() > 1) {
                        StringBuilder merged = new StringBuilder();
                        for (String query : queries) {
                            String one = BaiduMapToolGuard.withQuery(actualInput, query);
                            String oneOut = BaiduMapToolGuard.unwrapMcpContent(callback.call(one, toolContext));
                            if (!merged.isEmpty()) {
                                merged.append('\n');
                            }
                            merged.append(query).append('：')
                                    .append(BaiduMapToolGuard.formatSearchPlacesOutput(oneOut));
                        }
                        return merged.toString();
                    }
                    return BaiduMapToolGuard.formatSearchPlacesOutput(
                            BaiduMapToolGuard.unwrapMcpContent(callback.call(actualInput, toolContext)));
                }
                if ("map_geocode".equals(name)) {
                    String rawOutput = BaiduMapToolGuard.unwrapMcpContent(callback.call(actualInput, toolContext));
                    BaiduMapToolGuard.rememberGeocode(exec, rawOutput);
                    return BaiduMapToolGuard.formatGeocodeOutput(rawOutput);
                }
                return BaiduMapToolGuard.unwrapMcpContent(callback.call(actualInput, toolContext));
            }
        };
    }
}
