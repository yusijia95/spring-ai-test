package com.yusijia.quickstart;

import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import java.io.IOException;

@SpringBootTest
public class quickStartTest {

    @Test
    public void testDeepseek(@Autowired DeepSeekChatModel deepSeekChatModel,@Autowired ServletContext servletContext) throws IOException {
        String result = deepSeekChatModel.call("你叫什么");
//        System.out.println(result);

//        String response = deepSeekChatModel.call("你是谁？");
//        // 写入文件
//        java.nio.file.Files.write(
//                java.nio.file.Paths.get("deepseek_response.txt"),
//                response.getBytes(java.nio.charset.StandardCharsets.UTF_8)
//        );
        System.out.println("响应已写入 deepseek_response.txt 文件");
        System.out.println("file.encoding: " + System.getProperty("file.encoding"));
        System.out.println("输出流编码：" + System.out.charset());
        System.out.println("Tomcat version: " + servletContext.getServerInfo());
    }

    @Test
    public void testOllama(@Autowired OllamaChatModel ollamaChatModel) throws IOException {
//        String result = ollamaChatModel.call("你叫什么");
//        System.out.println(result);
        Flux<String> result = ollamaChatModel.stream("你好");
        result.toIterable().forEach(System.out::println);
//        result.toStream().forEach(System.out::println);
    }

}
