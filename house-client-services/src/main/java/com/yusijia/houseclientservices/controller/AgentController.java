package com.yusijia.houseclientservices.controller;

import com.yusijia.houseclientservices.agent.AgentPrompts;
import com.yusijia.houseclientservices.agent.Skill;
import com.yusijia.houseclientservices.agent.SkillSelector;
import com.yusijia.houseclientservices.agent.ToolRegistry;
import com.yusijia.houseclientservices.controller.dto.ChatRequest;
import com.yusijia.houseclientservices.tool.BaiduMapToolGuard;
import com.yusijia.houseclientservices.tool.ProjectTools;
import com.yusijia.houseclientservices.tool.SubscriptionTools;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final ChatClient houseServiceChatClient;
    private final SubscriptionTools subscriptionTools;
    private final ProjectTools projectTools;
    private final SkillSelector skillSelector;
    private final ToolRegistry toolRegistry;

    public AgentController(@Qualifier("houseServiceChatClient") ChatClient houseServiceChatClient,
                           SubscriptionTools subscriptionTools,
                           ProjectTools projectTools,
                           SkillSelector skillSelector,
                           ToolRegistry toolRegistry) {
        this.houseServiceChatClient = houseServiceChatClient;
        this.subscriptionTools = subscriptionTools;
        this.projectTools = projectTools;
        this.skillSelector = skillSelector;
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
        List<ToolCallback> toolCallbacks = toolRegistry.forSkills(skills);
        log.info("agent chat: conversationId={}, skills={}, tools={}",
                conversationId,
                skills.stream().map(Skill::name).collect(Collectors.toList()),
                toolCallbacks.stream().map(callback -> callback.getToolDefinition().name()).toList());

        BaiduMapToolGuard.clear();
        projectTools.clear();
        ChatResponse chatResponse;
        try {
            chatResponse = houseServiceChatClient.prompt()
                    .system(AgentPrompts.compose(skills))
                    .user(request.message())
                    .toolCallbacks(toolCallbacks)
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .chatResponse();
        } finally {
            BaiduMapToolGuard.clear();
        }
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
        if (text != null && (text.contains("认购已生成") || text.contains("认购业务已生成"))) {
            if (!subscriptionTools.consumeCreateSubscriptionCalled()) {
                return Flux.just("抱歉，认购操作未能完成，请您重新描述需求，我再为您办理。");
            }
        }

        return Flux.just(StringUtils.hasText(text) ? text : "抱歉，未能处理您的请求，请您重新描述。");
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
