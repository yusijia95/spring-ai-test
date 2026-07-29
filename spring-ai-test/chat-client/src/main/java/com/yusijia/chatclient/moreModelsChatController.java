//package com.yusijia.chatclient;
//
//import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
//import org.springframework.ai.chat.client.ChatClient;
//import org.springframework.ai.chat.model.ChatModel;
//import org.springframework.ai.chat.prompt.ChatOptions;
//import org.springframework.ai.deepseek.DeepSeekChatModel;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.http.MediaType;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RequestMethod;
//import org.springframework.web.bind.annotation.RestController;
//import reactor.core.publisher.Flux;
//
//import java.util.concurrent.ConcurrentHashMap;
//
//@RestController
//public class moreModelsChatController {
//
//    private final ConcurrentHashMap<String, ChatModel> platForms = new ConcurrentHashMap<String, ChatModel>();
//
//    public moreModelsChatController(DashScopeChatModel dashScopeChatModel, DeepSeekChatModel deepSeekChatModel) {
//        platForms.put("dashScope",dashScopeChatModel);
//        platForms.put("deepseek",deepSeekChatModel);
//    }
//
//    @RequestMapping(value = "/chat",method = RequestMethod.GET,produces = "text/stream;charset=UTF-8")
//    public Flux<String> stream(String message,moreModelsEntity moreModelsEntity){
//        String platform = moreModelsEntity.getPlatform();
//        ChatModel chatModel = platForms.get(platform);
//        if (chatModel == null) {
//            throw new IllegalArgumentException("Unknown platform: " + platform);
//        }
//        ChatClient.Builder builder = ChatClient.builder(chatModel);
//        ChatClient chatClient = builder.defaultOptions(ChatOptions.builder()
//                .temperature(moreModelsEntity.getTemperature())
//                .model(moreModelsEntity.getModel())
//                .build()).build();
//        Flux<String> content = chatClient.prompt().user(message).stream().content();
//        return content;
//    };
//
//    @RequestMapping(value = "/chatcall",method = RequestMethod.GET,produces = "text/stream;charset=UTF-8")
//    public String call(String message,moreModelsEntity moreModelsEntity){
//        String platform = moreModelsEntity.getPlatform();
//        ChatModel chatModel = platForms.get(platform);
//        if (chatModel == null) {
//            throw new IllegalArgumentException("Unknown platform: " + platform);
//        }
//        ChatClient.Builder builder = ChatClient.builder(chatModel);
//        ChatClient chatClient = builder.defaultOptions(ChatOptions.builder()
//                .temperature(moreModelsEntity.getTemperature())
//                .model(moreModelsEntity.getModel())
//                .build()).build();
//        String content = chatClient.prompt().user(message).call().content();
//        return content;
//    };
//}
