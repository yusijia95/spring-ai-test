package com.yusijia.houseclientservices.controller;

import com.yusijia.houseclientservices.agent.AgentPrompts;
import com.yusijia.houseclientservices.agent.RetrievalPlanner;
import com.yusijia.houseclientservices.agent.Skill;
import com.yusijia.houseclientservices.agent.SkillSelector;
import com.yusijia.houseclientservices.agent.ToolRegistry;
import com.yusijia.houseclientservices.controller.dto.ChatRequest;
import com.yusijia.houseclientservices.tool.BaiduMapToolGuard;
import com.yusijia.houseclientservices.tool.ProjectTools;
import com.yusijia.houseclientservices.tool.SubscriptionTools;
import com.yusijia.houseclientservices.tool.WebSearchTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final ChatClient houseServiceChatClient;
    private final SubscriptionTools subscriptionTools;
    private final ProjectTools projectTools;
    private final WebSearchTools webSearchTools;
    private final SkillSelector skillSelector;
    private final RetrievalPlanner retrievalPlanner;
    private final ToolRegistry toolRegistry;

    public AgentController(@Qualifier("houseServiceChatClient") ChatClient houseServiceChatClient,
                           SubscriptionTools subscriptionTools,
                           ProjectTools projectTools,
                           WebSearchTools webSearchTools,
                           SkillSelector skillSelector,
                           RetrievalPlanner retrievalPlanner,
                           ToolRegistry toolRegistry) {
        this.houseServiceChatClient = houseServiceChatClient;
        this.subscriptionTools = subscriptionTools;
        this.projectTools = projectTools;
        this.webSearchTools = webSearchTools;
        this.skillSelector = skillSelector;
        this.retrievalPlanner = retrievalPlanner;
        this.toolRegistry = toolRegistry;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody ChatRequest request) {
        if (request == null || !StringUtils.hasText(request.message())) {
            return Flux.just("请输入要办理的事项。");
        }
        String conversationId = StringUtils.hasText(request.conversationId())
                ? request.conversationId()
                : "default";

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

        String systemPrompt = AgentPrompts.compose(skills);
        if (retrieval != null && !retrieval.isBlank()) {
            systemPrompt = systemPrompt + "\n" + AgentPrompts.retrievalBlock(retrieval);
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

        BaiduMapToolGuard.clear();
        projectTools.clear();
        try {
            // 本轮没有业务工具（纯问答 / 检索结果已由系统注入）时走真流式：
            // 不需要"漏答补齐"和"认购防幻觉"这类对全文的校验，逐字返回体验更好。
            if (toolCallbacks.isEmpty()) {
                return houseServiceChatClient.prompt()
                        .system(systemPrompt)
                        .user(request.message())
                        .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                        .stream()
                        .content()
                        .transform(this::stripLeadingNoise)
                        .switchIfEmpty(Flux.just("抱歉，未能处理您的请求，请您重新描述。"));
            }
            return Flux.just(answerWithTools(systemPrompt, request.message(), toolCallbacks, conversationId));
        } finally {
            BaiduMapToolGuard.clear();
        }
    }

    /**
     * 有业务工具时的一次性调用：没法流式，因为下面三件事都需要拿到完整回答。
     * <ul>
     *   <li>模型漏答楼盘时用工具事实补齐"住宅与价格"小节；</li>
     *   <li>声称"认购已生成"但实际没调工具时拦截；</li>
     *   <li>清理开头孤立的标点。</li>
     * </ul>
     */
    private String answerWithTools(String systemPrompt, String message,
                                   List<ToolCallback> toolCallbacks, String conversationId) {
        ChatResponse chatResponse = houseServiceChatClient.prompt()
                .system(systemPrompt)
                .user(message)
                .toolCallbacks(toolCallbacks)
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
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
            String housingNote = projectTools.unansweredHousingNote(text);
            if (housingNote != null) {
                log.info("agent chat: 模型漏答楼盘，已补齐住宅与价格小节");
                text = housingNote + "\n\n" + text;
            }
        }

        // 防幻觉校验：如果模型声称"认购已生成"但 createSubscription 工具未被实际调用，拦截编造的响应
        if (text != null && (text.contains("认购已生成") || text.contains("认购业务已生成"))
                && !subscriptionTools.consumeCreateSubscriptionCalled()) {
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
