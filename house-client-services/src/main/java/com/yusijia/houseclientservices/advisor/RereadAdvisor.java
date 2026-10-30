package com.yusijia.houseclientservices.advisor;

import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;

import java.util.Map;

public class RereadAdvisor implements BaseAdvisor {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(RereadAdvisor.class);
    private static final String REREAD_PROMPT = """
            {contents}
            请重新阅读并确认：{contents}
            """;

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        // 请求之前重写提示词
        String contents = chatClientRequest.prompt().getContents();
        String renderedPrompt = PromptTemplate.builder().template(REREAD_PROMPT).build().render(Map.of("contents", contents));
//        logger.info("Reread Prompt: {}", renderedPrompt);
        ChatClientRequest build = chatClientRequest.mutate().prompt(Prompt.builder().content(renderedPrompt).build()).build();
        return build;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
