package com.yusijia.houseclientservices.store;

import com.yusijia.houseclientservices.domain.Subscription;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class SubscriptionStore {

    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    public List<Subscription> findAll() {
        return List.copyOf(subscriptions);
    }

    public Subscription create(String houseLocation, String buyerName, String buyerIdNo) {
        Subscription subscription = new Subscription(
                UUID.randomUUID().toString(),
                houseLocation.trim(),
                buyerName.trim(),
                buyerIdNo.trim(),
                Instant.now()
        );
        subscriptions.add(subscription);
        return subscription;
    }

    public boolean existsByLocationAndIdNo(String houseLocation, String buyerIdNo) {
        return subscriptions.stream().anyMatch(item ->
                item.houseLocation().equalsIgnoreCase(houseLocation.trim())
                        && item.buyerIdNo().equalsIgnoreCase(buyerIdNo.trim()));
    }

    public List<Subscription> find(String houseLocation, String buyerName) {
        boolean hasLocation = StringUtils.hasText(houseLocation);
        boolean hasName = StringUtils.hasText(buyerName);
        return subscriptions.stream()
                .filter(item -> {
                    boolean locationMatch = !hasLocation
                            || item.houseLocation().contains(houseLocation.trim());
                    boolean nameMatch = !hasName
                            || item.buyerName().contains(buyerName.trim());
                    return locationMatch && nameMatch;
                })
                .toList();
    }

    public Optional<Subscription> findById(String id) {
        return subscriptions.stream().filter(item -> item.id().equals(id)).findFirst();
    }

    public Optional<Subscription> deleteById(String id) {
        Optional<Subscription> found = findById(id);
        found.ifPresent(subscriptions::remove);
        return found;
    }
}
