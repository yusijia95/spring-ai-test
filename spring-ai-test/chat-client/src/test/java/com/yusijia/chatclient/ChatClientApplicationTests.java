package com.yusijia.chatclient;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ChatClientApplicationTests {

	@Test
	void contextLoads() {
	}

	@Test
	void testChatclient(@Autowired ChatClient.Builder chatClientBuilder) {
		ChatClient build = chatClientBuilder.build();
		String result = build.prompt().user("你好").call().content();
		System.out.println(result);
	}

}
