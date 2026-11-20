package com.yusijia.houseclientservices.security;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 登录用户上下文。
 * <p>
 * P0-1 是"假登录"骨架：不接真 SSO，登录接口只校验用户名是否在演示账号白名单里，
 * 命中即签发一个不透明会话令牌。{@code UserContext} 是这套骨架与真身份系统之间的
 * 唯一接缝——将来接 SSO 时，只需让 {@code UserContextFilter} 从 SSO 断言里构造
 * {@code UserContext}，下游（归属校验、权限点、审计）完全不用动。
 */
public record UserContext(
        Long userId,
        String username,
        String role,
        String displayName
) {

    /** 自助客户：能聊天、查自己的会话，但不能用坐席侧工具（如撤销他人认购）。 */
    public static final String ROLE_CUSTOMER = "customer";

    /** 客服坐席：额外拥有坐席侧权限点（见 {@link PermissionPoints}）。 */
    public static final String ROLE_AGENT = "agent";

    /** 派生属性，不参与序列化（否则 Jackson 会把它写进 JSON，反序列化时多出来的字段让 record creator 失败）。 */
    @JsonIgnore
    public boolean isAgent() {
        return ROLE_AGENT.equals(role);
    }

    @JsonIgnore
    public boolean isCustomer() {
        return ROLE_CUSTOMER.equals(role);
    }
}
