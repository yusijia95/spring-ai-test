package com.yusijia.houseclientservices.security;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示账号白名单（P0-1 假登录）。
 * <p>
 * 不接真 SSO / 不查 user_account 表，只用一个静态白名单模拟"这个用户名是合法登录用户"。
 * 登录接口收到 username，命中白名单就签发令牌，角色取白名单里的值。
 * <p>
 * 将来接真身份时：本类整体删除，{@code AuthService} 改调 SSO / 查 user_account，
 * 返回的 {@link UserContext} 结构不变，下游无感。
 * <p>
 * 默认账号：
 * <ul>
 *   <li>{@code customer1} → customer 角色，personId=5001（对应 V3 种子数据里的"张三"，
 *       自助客户只能对本人 data 操作）</li>
 *   <li>{@code agent1} → agent 角色，personId=null（坐席不绑定个人档案，可代任意客户）</li>
 * </ul>
 */
public final class DemoAccounts {

    private static final List<UserContext> DEFAULTS = List.of(
            new UserContext(1L, "customer1", UserContext.ROLE_CUSTOMER, "演示客户1", 5001L),
            new UserContext(2L, "agent1", UserContext.ROLE_AGENT, "演示坐席1", null)
    );

    private static final Map<String, UserContext> BY_USERNAME = build(DEFAULTS);

    private DemoAccounts() {
    }

    public static UserContext find(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return BY_USERNAME.get(username.trim());
    }

    private static Map<String, UserContext> build(List<UserContext> accounts) {
        Map<String, UserContext> map = new HashMap<>();
        for (UserContext u : accounts) {
            map.put(u.username(), u);
        }
        return map;
    }
}
