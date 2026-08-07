package com.yusijia.moremodulestructurtedoutput.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {
    @Bean
    public ChatClient planChatClient(DashScopeChatModel dashScopeChatModel,
                                 ChatMemory chatMemory) {
        DashScopeChatOptions dashScopeChatOptions = DashScopeChatOptions.builder()
                .temperature(0.4d)
                .build();
        return ChatClient.builder(dashScopeChatModel).defaultSystem("""
                # 票务助手任务拆分规则
                ## 1.要求
                ### 1.1 根据用户内容识别任务
                
                ## 2.任务
                ### 2.1 JobType:退票（CANCAEL） 要求用户提供姓名和预定号，或者从对话中获取
                ### 2.2 JobType:查票（QUERY） 要求用户提供预定号，或者从对话中获取
                ### 2.3 JobType:其他（OTHER）
                """)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultOptions(dashScopeChatOptions).build();
    }

    @Bean
    public ChatClient obtChatClient(DashScopeChatModel dashScopeChatModel,
                                     ChatMemory chatMemory) {
        DashScopeChatOptions dashScopeChatOptions = DashScopeChatOptions.builder()
                .temperature(1.7d)
                .build();
        return ChatClient.builder(dashScopeChatModel).defaultSystem("""
                # 你是一个航空客服代理
                """)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultOptions(dashScopeChatOptions).build();
    }
}
