package com.yusijia.houseclientservices.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据范围规则（自助客户 vs 坐席）的纯逻辑测试。
 */
class DataScopeTest {

    private final UserContext customer = new UserContext(1L, "customer1",
            UserContext.ROLE_CUSTOMER, "演示客户1", 5001L);
    private final UserContext customerWithoutPerson = new UserContext(3L, "customer2",
            UserContext.ROLE_CUSTOMER, "演示客户2", null);
    private final UserContext agent = new UserContext(2L, "agent1",
            UserContext.ROLE_AGENT, "演示坐席1", null);

    // ---------- 创建认购 ----------

    @Test
    void customerCanCreateForSelf() {
        assertThat(DataScope.createViolation(customer, List.of(5001L), null)).isNull();
        assertThat(DataScope.createViolation(customer, List.of(5001L), List.of())).isNull();
    }

    @Test
    void customerCannotCreateForOthers() {
        assertThat(DataScope.createViolation(customer, List.of(5002L), null))
                .contains("只能为本人");
        // 多人共同购买也拦：本骨架不支持自助添加共有人
        assertThat(DataScope.createViolation(customer, List.of(5001L, 5002L), null))
                .contains("只能为本人");
    }

    @Test
    void customerCannotBuyAsOrg() {
        assertThat(DataScope.createViolation(customer, null, List.of(6001L)))
                .contains("企业购房请联系客服坐席");
        assertThat(DataScope.createViolation(customer, List.of(5001L), List.of(6001L)))
                .contains("企业购房请联系客服坐席");
    }

    @Test
    void customerWithoutPersonRecordIsRejected() {
        assertThat(DataScope.createViolation(customerWithoutPerson, List.of(5001L), null))
                .contains("未关联个人档案");
    }

    @Test
    void agentCanCreateForAnyone() {
        assertThat(DataScope.createViolation(agent, List.of(5001L), null)).isNull();
        assertThat(DataScope.createViolation(agent, List.of(5002L), null)).isNull();
        assertThat(DataScope.createViolation(agent, null, List.of(6001L))).isNull();
    }

    @Test
    void missingUserIsFailClosed() {
        // 上下文缺失绝不能放行：ThreadLocal 在异步下会静默失效，这里必须守住
        assertThat(DataScope.createViolation(null, List.of(5001L), null))
                .contains("无法确认当前登录身份");
        assertThat(DataScope.cancelViolation(null, Set.of(5001L)))
                .contains("无法确认当前登录身份");
        assertThat(DataScope.queryViolation(null)).contains("无法确认当前登录身份");
    }

    // ---------- 撤销认购 ----------

    @Test
    void customerCanCancelOwnOrder() {
        assertThat(DataScope.cancelViolation(customer, Set.of(5001L))).isNull();
        // 本人是共有人之一也算本人名下
        assertThat(DataScope.cancelViolation(customer, Set.of(5001L, 5002L))).isNull();
    }

    @Test
    void customerCannotCancelOthersOrder() {
        assertThat(DataScope.cancelViolation(customer, Set.of(5002L)))
                .contains("只能撤销本人");
        assertThat(DataScope.cancelViolation(customer, Set.of())).contains("只能撤销本人");
    }

    @Test
    void agentCanCancelAnyOrder() {
        assertThat(DataScope.cancelViolation(agent, Set.of(5002L))).isNull();
        assertThat(DataScope.cancelViolation(agent, Set.of())).isNull();
    }

    // ---------- 查询过滤 ----------

    @Test
    void queryFilterIsSelfForCustomerAndNullForAgent() {
        assertThat(DataScope.queryViolation(customer)).isNull();
        assertThat(DataScope.queryViolation(agent)).isNull();
        assertThat(DataScope.queryOwnerFilter(customer)).isEqualTo(5001L);
        assertThat(DataScope.queryOwnerFilter(agent)).isNull();
    }

    @Test
    void queryFilterRefusesToGuessWithoutUser() {
        // 少了前置校验就直接抛，避免"返回 null = 不过滤"的静默越权
        assertThatThrownBy(() -> DataScope.queryOwnerFilter(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("queryViolation");
    }
}
