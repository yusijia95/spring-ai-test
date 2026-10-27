package com.yusijia.houseclientservices.controller;

import com.yusijia.houseclientservices.domain.view.SubscriptionView;
import com.yusijia.houseclientservices.service.SubscriptionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @GetMapping
    public List<SubscriptionView> list() {
        return subscriptionService.list();
    }

    @GetMapping("/search")
    public List<SubscriptionView> search(@RequestParam(required = false) String houseLocation,
                                         @RequestParam(required = false) String buyerName) {
        return subscriptionService.find(houseLocation, buyerName);
    }
}
