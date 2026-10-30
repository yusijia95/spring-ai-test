package com.yusijia.houseclientservices.controller;

import com.yusijia.houseclientservices.controller.dto.ChatRequest;
import com.yusijia.houseclientservices.tool.BaiduMapToolGuard;
import com.yusijia.houseclientservices.tool.SubscriptionTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final ChatClient houseServiceChatClient;
    private final SubscriptionTools subscriptionTools;
    private final ToolCallbackProvider[] mcpToolProviders;

    public AgentController(@Qualifier("houseServiceChatClient") ChatClient houseServiceChatClient,
                           SubscriptionTools subscriptionTools,
                           ToolCallbackProvider[] mcpToolProviders) {
        this.houseServiceChatClient = houseServiceChatClient;
        this.subscriptionTools = subscriptionTools;
        this.mcpToolProviders = mcpToolProviders;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody ChatRequest request) {
        if (request == null || !StringUtils.hasText(request.message())) {
            return Flux.just("请输入要办理的事项。");
        }
        String conversationId = StringUtils.hasText(request.conversationId())
                ? request.conversationId()
                : "default";

        BaiduMapToolGuard.clear();
        ChatResponse chatResponse;
        try {
            chatResponse = houseServiceChatClient.prompt()
                    .user(request.message())
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
        // #region agent log
        try {
            List<String> mcpNames = new ArrayList<>();
            if (mcpToolProviders != null) {
                for (ToolCallbackProvider provider : mcpToolProviders) {
                    if (provider == null) {
                        continue;
                    }
                    ToolCallback[] callbacks = provider.getToolCallbacks();
                    if (callbacks == null) {
                        continue;
                    }
                    for (ToolCallback callback : callbacks) {
                        mcpNames.add(callback.getToolDefinition().name());
                    }
                }
            }
            int toolCallCount = 0;
            String toolCallNames = "";
            if (chatResponse != null && chatResponse.getResult() != null
                    && chatResponse.getResult().getOutput() != null
                    && chatResponse.getResult().getOutput().getToolCalls() != null) {
                toolCallCount = chatResponse.getResult().getOutput().getToolCalls().size();
                toolCallNames = chatResponse.getResult().getOutput().getToolCalls().stream()
                        .map(call -> call.name())
                        .reduce((a, b) -> a + "," + b)
                        .orElse("");
            }
//            String preview = text == null ? "" : text.substring(0, Math.min(80, text.length())).replace("\"", "'").replace("\n", " ");
//            try (FileWriter fw = new FileWriter("D:\\ideaSpace\\study\\spring-ai-test\\debug-be087e.log", true)) {
//                fw.write("{\"sessionId\":\"be087e\",\"runId\":\"pre-fix\",\"hypothesisId\":\"H2\",\"location\":\"AgentController.java:chat\",\"message\":\"chat result vs registered mcp tools\",\"timestamp\":" + System.currentTimeMillis() + ",\"data\":{\"userPreview\":\"" + request.message().replace("\"", "'").replace("\n", " ") + "\",\"mcpProviderCount\":" + (mcpToolProviders == null ? -1 : mcpToolProviders.length) + ",\"mcpToolCount\":" + mcpNames.size() + ",\"mcpToolNames\":\"" + String.join(",", mcpNames).replace("\"", "'") + "\",\"finalToolCallCount\":" + toolCallCount + ",\"finalToolCallNames\":\"" + toolCallNames.replace("\"", "'") + "\",\"textPreview\":\"" + preview + "\"}}\n");
//            }
        } catch (Exception ignored) {
        }
        // #endregion

        // 防幻觉校验：如果模型声称"认购已生成"但 createSubscription 工具未被实际调用，拦截编造的响应
        if (text != null && (text.contains("认购已生成") || text.contains("认购业务已生成"))) {
            if (!subscriptionTools.consumeCreateSubscriptionCalled()) {
                return Flux.just("抱歉，认购操作未能完成，请您重新描述需求，我再为您办理。");
            }
        }

        return Flux.just(StringUtils.hasText(text) ? text : "抱歉，未能处理您的请求，请您重新描述。");
    }
}
