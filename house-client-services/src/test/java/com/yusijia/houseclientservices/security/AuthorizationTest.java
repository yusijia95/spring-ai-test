package com.yusijia.houseclientservices.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 角色 × 权限点矩阵 + 假登录白名单的纯逻辑测试（不依赖 Spring/Redis）。
 */
class AuthorizationTest {

    private final UserContext customer =
            new UserContext(1L, "customer1", UserContext.ROLE_CUSTOMER, "演示客户1");
    private final UserContext agent =
            new UserContext(2L, "agent1", UserContext.ROLE_AGENT, "演示坐席1");

    @Test
    void customer_has_chatAndReadPermissions() {
        assertThat(Authorization.hasPermission(customer, PermissionPoints.CHAT_USE)).isTrue();
        assertThat(Authorization.hasPermission(customer, PermissionPoints.CHAT_CONVERSATION)).isTrue();
        assertThat(Authorization.hasPermission(customer, PermissionPoints.DATA_HOUSE_SEARCH)).isTrue();
        assertThat(Authorization.hasPermission(customer, PermissionPoints.DATA_PERSON_SEARCH)).isTrue();
        assertThat(Authorization.hasPermission(customer, PermissionPoints.DATA_ORG_SEARCH)).isTrue();
        assertThat(Authorization.hasPermission(customer, PermissionPoints.SUBSCRIPTION_READ)).isTrue();
    }

    @Test
    void customer_canCreateAndCancelOwnSubscription() {
        // 自助客户的核心能力：给自己办认购、撤销。角色差异在数据范围（DataScope），不在权限点
        assertThat(Authorization.hasPermission(customer, PermissionPoints.SUBSCRIPTION_CREATE)).isTrue();
        assertThat(Authorization.hasPermission(customer, PermissionPoints.SUBSCRIPTION_CANCEL)).isTrue();
    }

    @Test
    void agent_hasAllPermissions() {
        for (String p : new String[]{
                PermissionPoints.CHAT_USE,
                PermissionPoints.CHAT_CONVERSATION,
                PermissionPoints.DATA_HOUSE_SEARCH,
                PermissionPoints.DATA_PERSON_SEARCH,
                PermissionPoints.DATA_ORG_SEARCH,
                PermissionPoints.SUBSCRIPTION_READ,
                PermissionPoints.SUBSCRIPTION_CREATE,
                PermissionPoints.SUBSCRIPTION_CANCEL}) {
            assertThat(Authorization.hasPermission(agent, p)).as("agent 应持有 %s", p).isTrue();
        }
    }

    @Test
    void nullUser_hasNoPermission() {
        assertThat(Authorization.hasPermission(null, PermissionPoints.CHAT_USE)).isFalse();
    }

    @Test
    void unknownUser_inDemoAccounts_isNull() {
        assertThat(DemoAccounts.find("nobody")).isNull();
        assertThat(DemoAccounts.find("")).isNull();
        assertThat(DemoAccounts.find(null)).isNull();
    }

    @Test
    void knownUsers_resolveFromDemoAccounts() {
        assertThat(DemoAccounts.find("customer1")).isNotNull();
        assertThat(DemoAccounts.find("customer1").role()).isEqualTo(UserContext.ROLE_CUSTOMER);
        assertThat(DemoAccounts.find("agent1").role()).isEqualTo(UserContext.ROLE_AGENT);
        // 大小写/空白不敏感
        assertThat(DemoAccounts.find("  agent1  ").userId()).isEqualTo(2L);
    }

    @Test
    void rolePredicates() {
        assertThat(customer.isCustomer()).isTrue();
        assertThat(customer.isAgent()).isFalse();
        assertThat(agent.isAgent()).isTrue();
        assertThat(agent.isCustomer()).isFalse();
    }
}
