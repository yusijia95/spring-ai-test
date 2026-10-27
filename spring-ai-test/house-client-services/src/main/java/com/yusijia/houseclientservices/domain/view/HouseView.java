package com.yusijia.houseclientservices.domain.view;

import java.math.BigDecimal;

/**
 * 房屋查询结果视图，供 AI 工具与前端展示。
 */
public record HouseView(
        String id,
        String projectName,
        String buildingNo,
        String roomNo,
        String fullAddress,
        BigDecimal buildingArea,
        BigDecimal referenceUnitPrice,
        Integer plannedUsage,
        boolean available
) {
}
