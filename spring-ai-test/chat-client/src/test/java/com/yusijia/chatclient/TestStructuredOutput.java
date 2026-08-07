package com.yusijia.chatclient;

import com.alibaba.cloud.ai.memory.redis.JedisRedisChatMemoryRepository;
import com.yusijia.chatclient.memory.TestRedisMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
public class TestStructuredOutput {

    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(TestStructuredOutput.class);

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
    public void testOutput() {
        Boolean flag = client.prompt().user("快递怎么还不到，我要退货。")
                .advisors(p -> p.param(ChatMemory.CONVERSATION_ID, "1"))
                .system("""
                        请判断用户是否表达了要投诉的意图。
                        只能用true 或者 false 来回答，不要输出多余的内容
                        """)
                .call().entity(Boolean.class);
        if (flag) {
            logger.info("投诉了");
        }else {
            logger.info("没投诉");
        }
    }

    public static class Address {
        private String name;
        private String phone;
        private String address;
        private String province;
        private String city;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public String getProvince() {
            return province;
        }

        public void setProvince(String province) {
            this.province = province;
        }

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }
    }

    @Test
    public void testEntityOutput() {
        Address address = client.prompt().user("收货人：张三，电话13057661538，地址江苏省南京市溧水区永阳镇大街小巷108号")
                .advisors(p -> p.param(ChatMemory.CONVERSATION_ID, "1"))
                .system("""
                        请从语句中读取出收货信息，将省份（如"江苏省"）填入 province，城市（如"南京市"）填入 city
                        """)
                .call().entity(Address.class);
        logger.info("{} {} {} {} {}", address.getName(), address.getPhone(), address.getAddress(), address.getProvince(), address.getCity());
        assertNotNull(address.getProvince());
        assertNotNull(address.getCity());
        System.out.println(address.getName());
    }
}
