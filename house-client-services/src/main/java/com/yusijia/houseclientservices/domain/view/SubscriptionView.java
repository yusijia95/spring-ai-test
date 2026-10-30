package com.yusijia.houseclientservices.domain.view;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 认购业务视图。购买方、房屋、开发企业信息全部取自签约时的快照，不受主数据变更影响。
 */
public record SubscriptionView(
        String id,
        String bizNo,
        Integer status,
        String statusText,
        BigDecimal totalPrice,
        BigDecimal unitPrice,
        List<String> buyerNames,
        List<Buyer> buyers,
        List<String> houseLocations,
        List<String> developerNames,
        LocalDateTime createdAt,
        LocalDateTime finishedAt
) {

    /**
     * 购买方快照。idType 为空表示单位购买方。
     */
    public record Buyer(String name, Integer idType, String idNo, String phone) {
    }
}
