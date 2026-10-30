package com.yusijia.tools.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.List;

@Service
public class ToolService {

    @Autowired
    TicketService ticketService;

//    @Tool(description = "退票")
//    public String cancel(@ToolParam(description = "姓名（必须是真实人名，如果无法识别，禁止调用该逻辑）") String name
//                , @ToolParam(description = "预定号") String orderId) {
//        //防止出现随意补全的情况，比如说“男”也被ai认定为是姓名
//        //1、加强提示词边界检测，如描述中有“退票”、“取消”、“取消订单”、“退订”等关键词，则返回“请勿使用此工具”
//        //2、加强后端校验，执行前先查询校验
//        ticketService.cancelTicket(name, orderId);
//        return "退票成功。";
//    };

    public String cancel(String name, String orderId) {
        //防止出现随意补全的情况，比如说“男”也被ai认定为是姓名
        //1、加强提示词边界检测，如描述中有“退票”、“取消”、“取消订单”、“退订”等关键词，则返回“请勿使用此工具”
        //2、加强后端校验，执行前先查询校验
        ticketService.cancelTicket(name, orderId);
        return "退票成功。";
    };

    @Tool(description = "获取指定位置的天气，根据位置自动推算经纬度")
    public String selectWeather(@ToolParam(description = "经度") String longitude
            , @ToolParam(description = "纬度") String latitude) {
        return "晴天。";
    };

    public List<ToolCallback> getToolCallbacks(ToolService toolService){
        Method cancel = ReflectionUtils.findMethod(ToolService.class, "cancel", String.class, String.class);
        ToolDefinition toolDefinition = ToolDefinition.builder()
                .name("cancel")
                .description("退票")
                //动态获取角色和tools的关系
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "name": {
                              "type": "string",
                              "description": "姓名（必须是真实人名，如果无法识别，禁止调用该逻辑）"
                            },
                            "orderId": {
                              "type": "string",
                              "description": "预定号（必填）"
                            }
                          },
                          "required": ["name", "orderId"]
                        }
                        """)
                .build();
        MethodToolCallback toolCallback = MethodToolCallback.builder()
                .toolDefinition(toolDefinition)
                .toolMethod(cancel)
                .toolObject(toolService)
                .build();
        return List.of(toolCallback);
    }
}
