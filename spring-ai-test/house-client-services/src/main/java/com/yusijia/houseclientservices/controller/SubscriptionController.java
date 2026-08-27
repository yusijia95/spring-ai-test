package com.yusijia.houseclientservices.controller;

import com.yusijia.houseclientservices.domain.Subscription;
import com.yusijia.houseclientservices.store.SubscriptionStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    private final SubscriptionStore subscriptionStore;

    public SubscriptionController(SubscriptionStore subscriptionStore) {
        this.subscriptionStore = subscriptionStore;
    }

    @GetMapping
    public List<Subscription> list() {
        return subscriptionStore.findAll();
    }
}
