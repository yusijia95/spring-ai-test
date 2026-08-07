package com.yusijia.tools.service;

import org.springframework.stereotype.Service;

@Service
public class TicketService {
    public void cancelTicket(String name, String orderId) {
        System.out.println("退票成功");
    }
}
