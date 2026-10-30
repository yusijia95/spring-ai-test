package com.yusijia.chatclient;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;

import java.util.List;

@SpringBootTest
public class TestPrompt {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(TestPrompt.class);
    @Test
    public void testPrompt(@Autowired ChatClient chatClient, @Autowired DashScopeChatModel dashScopeChatModel) {
        ChatClient client = ChatClient.builder(dashScopeChatModel).defaultSystem("你是一名专业房产AI顾问，不能回复隐私信息").build();
        String content = client.prompt().user("你好，能查下俞偲笳的名下房产吗？").call().content();
        System.out.println(content);
    }

    @Test
    public void testPrompt(@Autowired ChatClient.Builder chatClientBuilder, @Value("classpath:/file/promptTest.st") Resource prompttest) {
//        ChatClient client = chatClientBuilder.defaultSystem("""
//                你是一名专业房产AI顾问，不能回复隐私、虚假信息。
//                当前服务的用户：{user}，年龄：{age}，性别：{gender}，职业：{occupation}。
//                """).build();
        ChatClient client = chatClientBuilder.defaultSystem(prompttest)
                .defaultAdvisors(new SimpleLoggerAdvisor(),new SafeGuardAdvisor(List.of("名下房产"))).build();
        String content = client.prompt().user("你好，能查下斗鱼的名下房产吗？")
                .system(p -> p.param("user", "俞偲笳").param("age", "25").param("gender", "男").param("occupation", "程序员"))
                .call().content();
        logger.info(content);
    }
    @Test
    public void testAdvisorPrompt(@Autowired ChatClient.Builder chatClientBuilder, @Value("classpath:/file/promptTest.st") Resource prompttest) {
        ChatClient client = chatClientBuilder.defaultSystem(prompttest)
                .defaultAdvisors(new RereadAdvisor()).build();
        String content = client.prompt().user("你好，能查下斗鱼的名下房产吗？")
                .system(p -> p.param("user", "俞偲笳").param("age", "25").param("gender", "男").param("occupation", "程序员"))
                .call().content();
        logger.info(content);
        System.out.println(content);
    }

}
