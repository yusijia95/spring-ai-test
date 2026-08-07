package com.yusijia.moremodulestructurtedoutput.controller;

import com.yusijia.moremodulestructurtedoutput.common.AiJob;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@RestController
public class MultiModelsController {
    @Autowired
    @Qualifier("planChatClient")
    private ChatClient planChatClient;
    @Autowired
    @Qualifier("obtChatClient")
    private ChatClient obtChatClient;
    @RequestMapping(value = "/stream",method = RequestMethod.GET,produces = "text/stream;charset=UTF-8")
    public Flux<String> stream(String message){
        Sinks.Many<String> sinks = Sinks.many().unicast().onBackpressureBuffer();
        sinks.tryEmitNext("开始处理</br>");
        new Thread(() -> {
            AiJob.Job Job = planChatClient.prompt().user(message).call().entity(AiJob.Job.class);
            switch (Job.jobType()){
                case CANCEL -> {
                    System.out.println(Job);
                    if (Job.params().isEmpty()){
                        sinks.tryEmitNext("请输入姓名和预定号");
                    }else {
                        sinks.tryEmitNext("退票完成。");
                    }
                }
                case QUERY -> {
                    System.out.println(Job);
                    if (Job.params().isEmpty()){
                        sinks.tryEmitNext("请输入预定号");
                    }else {
                        sinks.tryEmitNext("查询完成。");
                    }
                }
                case OTHER -> {
                    Flux<String> content = obtChatClient.prompt().user(message).stream().content();
                    content.doOnNext(s -> sinks.tryEmitNext(s))
                            .doOnComplete(() -> sinks.tryEmitComplete())
                            .subscribe() ;
                }
                default -> {
                    sinks.tryEmitNext("解析失败");
                }
            }
        }).start();
        return sinks.asFlux();
    };
}
