package com.yusijia.houseclientservices.domain.view;

/**
 * 单位客户查询结果视图。
 */
public record OrgView(
        String id,
        Integer unitType,
        String unitTypeText,
        String fullName,
        String idNo,
        String contact
) {
}
