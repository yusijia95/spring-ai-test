package com.yusijia.houseclientservices.domain;

import java.time.Instant;

public record Subscription(
        String id,
        String houseLocation,
        String buyerName,
        String buyerIdNo,
        Instant createdAt
) {
}
