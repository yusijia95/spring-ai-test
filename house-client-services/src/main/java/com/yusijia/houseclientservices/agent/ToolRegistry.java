package com.yusijia.houseclientservices.agent;

import com.yusijia.houseclientservices.tool.BaiduMapToolGuard;
import com.yusijia.houseclientservices.tool.ProjectTools;
import com.yusijia.houseclientservices.tool.SubscriptionTools;
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
 * 业务工具只有 8 个，按技能裁剪是为了控制注意力；MCP 的 14 个工具里大部分永远用不到，
 * 白名单直接由 SKILL.md 的 tools 推导，在注册期裁掉未声明的部分。
 * 按技能裁剪后，单次请求只挂 2~4 个工具，避免 schema 烧 token、分散模型注意力。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolCallback> byName = new LinkedHashMap<>();

    public ToolRegistry(SubscriptionTools subscriptionTools,
                        ProjectTools projectTools,
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
                .toolObjects(subscriptionTools, projectTools)
                .build()
                .getToolCallbacks();
        for (ToolCallback callback : businessCallbacks) {
            byName.put(callback.getToolDefinition().name(), callback);
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
                        byName.put(name, wrap(callback));
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
     * 最终结果为空时退回全量（安全兜底）。
     *
     * @param skills 选中的技能列表
     * @return 工具回调列表
     */
    public List<ToolCallback> forSkills(List<Skill> skills) {
        if (skills == null || skills.isEmpty()) {
            return List.copyOf(byName.values());
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
        return selected.isEmpty() ? List.copyOf(byName.values()) : selected;
    }

    /** 全部已注册工具名，便于排查 */
    public Set<String> registeredToolNames() {
        return Set.copyOf(byName.keySet());
    }

    private ToolCallback wrap(ToolCallback callback) {
        return new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return callback.getToolDefinition();
            }

            @Override
            public String call(String toolInput) {
                String name = callback.getToolDefinition().name();
                String actualInput = toolInput;
                if ("map_directions".equals(name)) {
                    String guardMessage = BaiduMapToolGuard.directionsGuard(toolInput);
                    if (guardMessage != null) {
                        return guardMessage;
                    }
                    return BaiduMapToolGuard.formatDirectionsOutput(
                            BaiduMapToolGuard.unwrapMcpContent(callback.call(actualInput)));
                }
                if ("map_search_places".equals(name)) {
                    actualInput = BaiduMapToolGuard.rewriteSearchPlacesInput(toolInput);
                    List<String> queries = BaiduMapToolGuard.splitQueries(actualInput);
                    if (queries.size() > 1) {
                        StringBuilder merged = new StringBuilder();
                        for (String query : queries) {
                            String one = BaiduMapToolGuard.withQuery(actualInput, query);
                            String oneOut = BaiduMapToolGuard.unwrapMcpContent(callback.call(one));
                            if (!merged.isEmpty()) {
                                merged.append('\n');
                            }
                            merged.append(query).append('：')
                                    .append(BaiduMapToolGuard.formatSearchPlacesOutput(oneOut));
                        }
                        return merged.toString();
                    }
                    return BaiduMapToolGuard.formatSearchPlacesOutput(
                            BaiduMapToolGuard.unwrapMcpContent(callback.call(actualInput)));
                }
                if ("map_geocode".equals(name)) {
                    String rawOutput = BaiduMapToolGuard.unwrapMcpContent(callback.call(actualInput));
                    BaiduMapToolGuard.rememberGeocode(rawOutput);
                    return BaiduMapToolGuard.formatGeocodeOutput(rawOutput);
                }
                return BaiduMapToolGuard.unwrapMcpContent(callback.call(actualInput));
            }
        };
    }
}
