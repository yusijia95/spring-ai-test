package com.yusijia.tools.controller;

import com.yusijia.tools.service.ToolService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ToolController {
    ChatClient build;

    public ToolController(ChatClient.Builder builder, ToolService toolService) {
        build = builder.defaultOptions(ChatOptions.builder().temperature(1.4d).build())
//                .defaultTools(toolService)
                .defaultToolCallbacks(toolService.getToolCallbacks(toolService))
                .build();
    }

    @RequestMapping(value = "/tool",method = RequestMethod.GET,produces = "text/stream;charset=UTF-8")
    public String tool(String message){
        return build.prompt().user(message).call().content();
    }
}
