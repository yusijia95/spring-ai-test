package com.yusijia.houseclientservices.domain.view;

/**
 * 个人客户查询结果视图，证件号已脱敏。
 */
public record PersonView(
        String id,
        String fullName,
        String idNoMasked,
        String phone
) {
}
