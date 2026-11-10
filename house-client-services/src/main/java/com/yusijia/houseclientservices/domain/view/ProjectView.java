package com.yusijia.houseclientservices.domain.view;

import java.math.BigDecimal;

/**
 * 楼盘查询结果视图，供 AI 工具回答"某区域有哪些住宅、价格多少"。
 */
public record ProjectView(
        String projectName,
        String location,
        String developerName,
        int totalRooms,
        int availableRooms,
        BigDecimal minUnitPrice,
        BigDecimal maxUnitPrice,
        BigDecimal minArea,
        BigDecimal maxArea
) {
}
