package com.yusijia.tools.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ToolService {

    @Autowired
    TicketService ticketService;

    @Tool(description = "退票")
    public String cancel(@ToolParam(description = "姓名（必须是真实人名，如果无法识别，禁止调用该逻辑）") String name
                , @ToolParam(description = "预定号") String orderId) {
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
}
