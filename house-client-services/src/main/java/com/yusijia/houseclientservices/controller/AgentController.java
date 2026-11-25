package com.yusijia.houseclientservices.controller;

import com.yusijia.houseclientservices.agent.AgentPrompts;
import com.yusijia.houseclientservices.agent.RetrievalPlanner;
import com.yusijia.houseclientservices.agent.Skill;
import com.yusijia.houseclientservices.agent.SkillSelector;
import com.yusijia.houseclientservices.agent.ToolRegistry;
import com.yusijia.houseclientservices.agent.memory.AgentChatMemory;
import com.yusijia.houseclientservices.controller.dto.ChatRequest;
import com.yusijia.houseclientservices.security.Authorization;
import com.yusijia.houseclientservices.security.PermissionPoints;
import com.yusijia.houseclientservices.security.UserContext;
import com.yusijia.houseclientservices.service.AgentAuditService;
import com.yusijia.houseclientservices.service.ConversationService;
import jakarta.servlet.http.HttpServletRequest;
import com.yusijia.houseclientservices.tool.ProjectTools;
import com.yusijia.houseclientservices.tool.SessionStateTools;
import com.yusijia.houseclientservices.tool.ToolExecutionContext;
import com.yusijia.houseclientservices.tool.WebSearchTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final ChatClient houseServiceChatClient;
    private final ProjectTools projectTools;
    private final WebSearchTools webSearchTools;
    private final SkillSelector skillSelector;
    private final RetrievalPlanner retrievalPlanner;
    private final ToolRegistry toolRegistry;
    private final AgentChatMemory agentChatMemory;
    private final SessionStateTools sessionStateTools;
    private final ConversationService conversationService;
    private final AgentAuditService agentAuditService;
    private final String chatModel;

    public AgentController(@Qualifier("houseServiceChatClient") ChatClient houseServiceChatClient,
                           ProjectTools projectTools,
                           WebSearchTools webSearchTools,
                           SkillSelector skillSelector,
                           RetrievalPlanner retrievalPlanner,
                           ToolRegistry toolRegistry,
                           AgentChatMemory agentChatMemory,
                           SessionStateTools sessionStateTools,
                           ConversationService conversationService,
                           AgentAuditService agentAuditService,
                           @Value("${agent.chat.model:qwen-max}") String chatModel) {
        this.houseServiceChatClient = houseServiceChatClient;
        this.projectTools = projectTools;
        this.webSearchTools = webSearchTools;
        this.skillSelector = skillSelector;
        this.retrievalPlanner = retrievalPlanner;
        this.toolRegistry = toolRegistry;
        this.agentChatMemory = agentChatMemory;
        this.sessionStateTools = sessionStateTools;
        this.conversationService = conversationService;
        this.agentAuditService = agentAuditService;
        this.chatModel = chatModel;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody ChatRequest request, HttpServletRequest http) {
        long startTime = System.currentTimeMillis();
        if (request == null || !StringUtils.hasText(request.message())) {
            return Flux.just("请输入要办理的事项。");
        }
        // P0-1：先鉴权（未登录/无权限抛 AccessDeniedException -> 403），再做会话归属校验。
        UserContext user = Authorization.requirePermission(http, PermissionPoints.CHAT_USE);
        // 归属校验：前端传了 conversationId 就校验归当前用户；没传则新建一条归当前用户的会话，
        // 返回权威 id。多实例 + LB 下这道校验防止 A 客户拿 B 客户的 conversationId 读到 B 的记忆。
        String conversationId = conversationService.getOrCreate(user, request.conversationId());
        if (!StringUtils.hasText(request.conversationId())) {
            log.info("agent chat: 请求未带 conversationId，已新建会话 id={} userId={}", conversationId, user.userId());
        }

        // A2：绑定会话与 userId，Redis key 带 userId 前缀（chat:{userId}:{cid}:...）
        // 用 conversationId 做键而非全局字段：singleton Bean 并发下全局字段会互相覆盖
        agentChatMemory.addUserScope(conversationId, String.valueOf(user.userId()));

        // 按选中的技能拼装提示词与工具：没被选中的技能不下发，避免上下文和工具槽位被稀释
        List<Skill> skills = skillSelector.select(conversationId, request.message());

        // 联网检索由系统主导：先规划出多条 query 并发检索，模型只负责依据资料作答。
        // 规划失败或不需要检索时（空列表），退回"模型自己调 webSearch"的旧路径。
        String retrieval = null;
        if (skills.stream().anyMatch(skill -> "web-search".equals(skill.name()))) {
            List<String> queries = retrievalPlanner.plan(request.message());
            if (queries.isEmpty()) {
                log.info("agent chat: 检索规划为空，退回模型自主检索");
            } else {
                retrieval = webSearchTools.searchMany(request.message(), queries);
                log.info("agent chat: 检索规划 queries={}", queries);
            }
        }

        // 顺序：硬约束 + 技能流程 → 会话事实 → 更早对话摘要。
        // 状态与摘要都在技能之后：事实优先于流程说明里的默认行为。
        // 检索结果不进这里：它是外部不可信文本，进 system 就等于拿到最高指令位。
        String systemPrompt = AgentPrompts.compose(skills, user);
        Map<String, String> sessionState = sessionStateTools.snapshot(String.valueOf(user.userId()), conversationId);
        String sessionBlock = AgentPrompts.sessionBlock(sessionState);
        if (!sessionBlock.isEmpty()) {
            systemPrompt = systemPrompt + "\n" + sessionBlock;
        }
        String digestBlock = AgentPrompts.digestBlock(agentChatMemory.digest(conversationId));
        if (!digestBlock.isEmpty()) {
            systemPrompt = systemPrompt + "\n" + digestBlock;
        }
        // 检索正文搬去 user 消息后，system 只补纯格式约束（不含正文，安全）
        if (retrieval != null && !retrieval.isBlank()) {
            systemPrompt = systemPrompt + "\n" + AgentPrompts.retrievalFormatBlock();
        }

        List<ToolCallback> toolCallbacks = toolRegistry.forSkills(skills);
        if (retrieval != null && !retrieval.isBlank()) {
            // 检索已经做完，把工具摘掉，模型的"要不要再查"决策权在这里被收回
            toolCallbacks = toolCallbacks.stream()
                    .filter(callback -> !"webSearch".equals(callback.getToolDefinition().name()))
                    .toList();
        }
        log.info("agent chat: conversationId={}, skills={}, tools={}",
                conversationId,
                skills.stream().map(Skill::name).collect(Collectors.toList()),
                toolCallbacks.stream().map(callback -> callback.getToolDefinition().name()).toList());

        // 本轮工具上下文：登录用户、会话 ID、外部内容标记、工具事实全部装在这里，
        // 由 prompt().toolContext(...) 显式随请求下传，不依赖线程（ThreadLocal 在流式/并行下会静默失效）。
        ToolExecutionContext exec = new ToolExecutionContext(user, conversationId);
        if (retrieval != null && !retrieval.isBlank()) {
            // 本轮读了外部网页正文：写工具在 ToolRegistry 里按此标记熔断
            exec.markExternalContentSeen();
        }
        List<String> toolNames = toolCallbacks.stream()
                .map(cb -> cb.getToolDefinition().name()).toList();
        boolean hasRetrieval = retrieval != null && !retrieval.isBlank();
        Map<String, Object> toolContext = Map.of(ToolExecutionContext.KEY, exec);

        // 本轮没有业务工具（纯问答 / 检索结果已由系统注入）时走真流式：
        // 不需要"漏答补齐"和"认购防幻觉"这类对全文的校验，逐字返回体验更好。
        if (toolCallbacks.isEmpty()) {
            Flux<String> flux = withUserMessages(houseServiceChatClient.prompt(), systemPrompt, retrieval, request.message())
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .toolContext(toolContext)
                    .stream()
                    .content()
                    .transform(this::stripLeadingNoise)
                    .switchIfEmpty(Flux.just("抱歉，未能处理您的请求，请您重新描述。"));
            return attachSummary(flux, user, conversationId, skills, toolNames, hasRetrieval, exec, startTime);
        }
        String answer = answerWithTools(systemPrompt, retrieval, request.message(), toolCallbacks,
                conversationId, exec);
        logSummary(conversationId, skills, toolNames, hasRetrieval, answer, startTime);
        // P0-5：同步写审计（有工具路径拿得到完整工具调用记录），写失败不影响已生成的回答
        agentAuditService.record(user, conversationId, skillNames(skills), exec, chatModel,
                System.currentTimeMillis() - startTime);
        return Flux.just(answer);
    }

    /**
     * 给流式 Flux 挂一个 doOnComplete/doOnError，结束时打结构化摘要日志并落审计。
     * 流式路径拿不到完整回复文本，只记 token 数和耗时。
     */
    private Flux<String> attachSummary(Flux<String> flux, UserContext user, String conversationId,
                                       List<Skill> skills, List<String> toolNames,
                                       boolean hasRetrieval, ToolExecutionContext exec, long startTime) {
        java.util.concurrent.atomic.AtomicInteger chunkCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger charCount = new java.util.concurrent.atomic.AtomicInteger(0);
        return flux
                .doOnNext(chunk -> {
                    if (chunk != null) {
                        chunkCount.incrementAndGet();
                        charCount.addAndGet(chunk.length());
                    }
                })
                .doOnComplete(() -> {
                    logSummary(conversationId, skills, toolNames, hasRetrieval, null, startTime);
                    // P0-5：流式路径（无工具）也要落一行审计；正常完成才记，中途异常不算一轮
                    agentAuditService.record(user, conversationId, skillNames(skills), exec, chatModel,
                            System.currentTimeMillis() - startTime);
                })
                .doOnError(e -> log.warn("agent chat 流式异常: conversationId={}, error={}", conversationId, e.getMessage()));
    }

    private List<String> skillNames(List<Skill> skills) {
        return skills.stream().map(Skill::name).toList();
    }

    /**
     * 结构化摘要日志：一行汇总本轮关键信息，便于 grep 和监控。
     * 格式：agent summary | cid=... | skills=... | tools=... | retrieval=Y/N | chunks=N | chars=N | duration=NNNms
     */
    private void logSummary(String conversationId, List<Skill> skills, List<String> toolNames,
                            boolean hasRetrieval, String fullAnswer, long startTime) {
        long duration = System.currentTimeMillis() - startTime;
        String skillNames = skills.stream().map(Skill::name).collect(Collectors.joining(","));
        int chars = fullAnswer != null ? fullAnswer.length() : -1;
        log.info("agent summary | cid={} | skills=[{}] | tools=[{}] | retrieval={} | answer_chars={} | duration={}ms",
                conversationId, skillNames, toolNames, hasRetrieval ? "Y" : "N", chars, duration);
    }

    /**
     * 拼装请求的 system 段与消息序列。
     * <p>
     * 检索资料必须作为<b>独立 user 消息</b>、排在客户原问题之前下发：
     * <ul>
     *   <li>{@code user(String)} 是覆盖语义（{@code this.userText = text}），连写两次只剩最后一条，
     *       所以用 {@code messages(...)} 承载资料——它排在 {@code user(text)} 之前，
     *       天然形成"资料 → 问题"的顺序；</li>
     *   <li>记忆顾问 {@code MessageChatMemoryAdvisor} 只取最后一条 user 消息写记忆，
     *       客户原问题才进历史，检索正文不会被持久化并在后续轮次反复放大。</li>
     * </ul>
     */
    private ChatClient.ChatClientRequestSpec withUserMessages(ChatClient.ChatClientRequestSpec spec,
                                                             String systemPrompt, String retrieval, String message) {
        ChatClient.ChatClientRequestSpec withSystem = spec.system(systemPrompt);
        if (retrieval == null || retrieval.isBlank()) {
            return withSystem.user(message);
        }
        return withSystem
                .messages(new UserMessage(AgentPrompts.untrustedBlock(retrieval)))
                .user(message);
    }

    /**
     * 有业务工具时的一次性调用：没法流式，因为下面三件事都需要拿到完整回答。
     * <ul>
     *   <li>模型漏答楼盘时用工具事实补齐"住宅与价格"小节；</li>
     *   <li>声称"认购已生成"但实际没调工具时拦截；</li>
     *   <li>清理开头孤立的标点。</li>
     * </ul>
     */
    private String answerWithTools(String systemPrompt, String retrieval, String message,
                                   List<ToolCallback> toolCallbacks, String conversationId,
                                   ToolExecutionContext exec) {
        ChatResponse chatResponse = withUserMessages(houseServiceChatClient.prompt(), systemPrompt, retrieval, message)
                .toolCallbacks(toolCallbacks)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .toolContext(Map.of(ToolExecutionContext.KEY, exec))
                .call()
                .chatResponse();
        String text = chatResponse == null || chatResponse.getResult() == null
                || chatResponse.getResult().getOutput() == null
                ? null
                : chatResponse.getResult().getOutput().getText();

        // 清理模型偶发的前导噪声（如孤立的"："），只在清理后仍有内容时才替换
        text = trimLeadingNoise(text);

        // 兜底：模型查了楼盘却把"住宅与价格"这一问吞掉时，用工具事实补齐
        if (StringUtils.hasText(text)) {
            String housingNote = projectTools.unansweredHousingNote(exec, text);
            if (housingNote != null) {
                log.info("agent chat: 模型漏答楼盘，已补齐住宅与价格小节");
                text = housingNote + "\n\n" + text;
            }
        }

        // 防幻觉校验：如果模型声称"认购已生成"但 createSubscription 工具未被实际调用，拦截编造的响应
        if (text != null && (text.contains("认购已生成") || text.contains("认购业务已生成"))
                && !exec.createSubscriptionCalled()) {
            return "抱歉，认购操作未能完成，请您重新描述需求，我再为您办理。";
        }

        return StringUtils.hasText(text) ? text : "抱歉，未能处理您的请求，请您重新描述。";
    }

    /**
     * 流式输出时逐块去掉开头噪声：收到第一段有内容的文本之前，把标点/空白块丢掉。
     */
    private Flux<String> stripLeadingNoise(Flux<String> source) {
        return Flux.defer(() -> {
            AtomicBoolean started = new AtomicBoolean(false);
            return source.handle((chunk, sink) -> {
                if (chunk == null || chunk.isEmpty()) {
                    return;
                }
                if (started.get()) {
                    sink.next(chunk);
                    return;
                }
                String cleaned = LEADING_NOISE.matcher(chunk).replaceFirst("");
                if (!cleaned.isEmpty()) {
                    started.set(true);
                    sink.next(cleaned);
                }
            });
        });
    }

    /** 模型偶尔会以孤立的"："或空行开头，前端会渲染成一个孤零零的冒号 */
    private static final Pattern LEADING_NOISE = Pattern.compile("^[\\s：:，,。、；;]+");

    private static String trimLeadingNoise(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        String trimmed = LEADING_NOISE.matcher(text).replaceFirst("");
        return StringUtils.hasText(trimmed) ? trimmed : text;
    }
}
