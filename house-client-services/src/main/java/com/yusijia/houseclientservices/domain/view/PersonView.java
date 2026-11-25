package com.yusijia.houseclientservices.domain.view;

/**
 * 个人客户查询结果视图。
 * <p>
 * 证件号始终脱敏；手机号默认脱敏，仅持有 {@code data:person:phone:read}（仅坐席）时返回明文。
 */
public record PersonView(
        String id,
        String fullName,
        String idNoMasked,
        String phone
) {
}
