package com.yusijia.chatclient;

import com.alibaba.cloud.ai.memory.redis.JedisRedisChatMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@SpringBootTest
public class TestRedisMemory {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(TestRedisMemory.class);

    @TestConfiguration
    static class TestConfig {
        @Value("${spring.ai.chat.memory.redis.port}")
        private int port;
        @Value("${spring.ai.chat.memory.redis.host}")
        private String host;
        @Value("${spring.ai.chat.memory.redis.password}")
        private String password;
        @Value("${spring.ai.chat.memory.redis.timeout}")
        private int timeout;
        @Bean
        public JedisRedisChatMemoryRepository chatMemoryRepository() {
            return JedisRedisChatMemoryRepository.builder()
                    .host(host)
                    .port(port)
                    .password(password)
                    .timeout(timeout)
                    .build();
        }
        @Bean
        public ChatMemory chatMemory(@Autowired JedisRedisChatMemoryRepository chatMemoryRepository) {
            return MessageWindowChatMemory
                    .builder()
                    .maxMessages(10)
                    .chatMemoryRepository(chatMemoryRepository)
                    .build();
        }
    }

    ChatClient client;

    @BeforeEach
    public void init(@Autowired ChatClient.Builder chatClientBuilder, @Autowired ChatMemory chatMemory) {
        client = chatClientBuilder
                .defaultAdvisors(PromptChatMemoryAdvisor.builder(chatMemory).build()).build();
    }

    @Test
    public void testAdvisorMemory() {
        String content = client.prompt().user("你好，我叫俞偲笳")
                .advisors(p->p.param(ChatMemory.CONVERSATION_ID,"1"))
                .call().content();
        String content2 = client.prompt().user("你好，我叫什么")
                .advisors(p->p.param(ChatMemory.CONVERSATION_ID,"1"))
                .call().content();
        String content3 = client.prompt().user("你好，我叫什么")
                .advisors(p->p.param(ChatMemory.CONVERSATION_ID,"2"))
                .call().content();
        logger.info(content);
        logger.info(content2);
        logger.info(content3);
    }

}
