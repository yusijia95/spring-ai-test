package com.yusijia.houseclientservices.controller;

import com.yusijia.houseclientservices.controller.dto.ChatRequest;
import com.yusijia.houseclientservices.tool.SubscriptionTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final ChatClient houseServiceChatClient;
    private final SubscriptionTools subscriptionTools;

    public AgentController(@Qualifier("houseServiceChatClient") ChatClient houseServiceChatClient,
                           SubscriptionTools subscriptionTools) {
        this.houseServiceChatClient = houseServiceChatClient;
        this.subscriptionTools = subscriptionTools;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody ChatRequest request) {
        if (request == null || !StringUtils.hasText(request.message())) {
            return Flux.just("请输入要办理的事项。");
        }
        String conversationId = StringUtils.hasText(request.conversationId())
                ? request.conversationId()
                : "default";

        // 使用 call() 获取完整响应（包含工具调用元数据），以便验证工具是否被实际调用
        String text = houseServiceChatClient.prompt()
                .user(request.message())
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        // 防幻觉校验：如果模型声称"认购已生成"但 createSubscription 工具未被实际调用，拦截编造的响应
        if (text != null && (text.contains("认购已生成") || text.contains("认购业务已生成"))) {
            if (!subscriptionTools.consumeCreateSubscriptionCalled()) {
                return Flux.just("抱歉，认购操作未能完成，请您重新描述需求，我再为您办理。");
            }
        }

        return Flux.just(StringUtils.hasText(text) ? text : "抱歉，未能处理您的请求，请您重新描述。");
    }
}
