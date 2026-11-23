package com.yusijia.houseclientservices;

import com.yusijia.houseclientservices.security.UserContext;
import com.yusijia.houseclientservices.security.UserContextHolder;
import com.yusijia.houseclientservices.tool.SubscriptionTools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具层的数据范围校验（自助客户只能操作本人数据）。
 * 依赖 V3 种子数据：房屋 4001/4002，个人 5001(张三)/5002(李四)。
 * 事务内执行并回滚，不污染本地库。
 */
@SpringBootTest
@Transactional
class SubscriptionDataScopeTest {

    private static final Pattern BIZ_NO = Pattern.compile("编号=([^，]+)");

    @Autowired
    private SubscriptionTools tools;

    private final UserContext customer5001 = new UserContext(1L, "customer1",
            UserContext.ROLE_CUSTOMER, "演示客户1", 5001L);
    private final UserContext agent = new UserContext(2L, "agent1",
            UserContext.ROLE_AGENT, "演示坐席1", null);

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @Test
    void customerCanCreateOwnSubscription() {
        UserContextHolder.set(customer5001);

        String result = tools.createSubscription("4001", "5001", "", true);

        assertThat(result).contains("认购业务已生成");
        assertThat(result).contains("张三");
    }

    @Test
    void customerCannotCreateForOtherPerson() {
        UserContextHolder.set(customer5001);

        String result = tools.createSubscription("4001", "5002", "", true);

        assertThat(result).contains("生成失败").contains("只能为本人办理认购");
    }

    @Test
    void customerPreviewIsAlsoBlocked() {
        UserContextHolder.set(customer5001);

        String result = tools.createSubscription("4001", "5002", "", false);

        assertThat(result).contains("生成失败").contains("只能为本人办理认购");
    }

    @Test
    void customerCannotCreateAsOrg() {
        UserContextHolder.set(customer5001);

        String result = tools.createSubscription("4001", "", "6001", true);

        assertThat(result).contains("生成失败").contains("企业购房请联系客服坐席");
    }

    @Test
    void agentCanCreateForAnyPerson() {
        UserContextHolder.set(agent);

        String result = tools.createSubscription("4001", "5002", "", true);

        assertThat(result).contains("认购业务已生成").contains("李四");
    }

    @Test
    void customerCannotCancelOthersSubscription() {
        UserContextHolder.set(agent);
        String bizNo = extractBizNo(tools.createSubscription("4002", "5002", "", true));

        UserContextHolder.set(customer5001);
        String result = tools.cancelSubscription(bizNo, true);

        assertThat(result).contains("撤销失败").contains("只能撤销本人名下的认购");
    }

    @Test
    void customerCanCancelOwnSubscription() {
        UserContextHolder.set(customer5001);
        String bizNo = extractBizNo(tools.createSubscription("4002", "5001", "", true));

        String result = tools.cancelSubscription(bizNo, true);

        assertThat(result).contains("认购业务已撤销");
    }

    @Test
    void customerFindSeesOnlyOwnSubscriptions() {
        UserContextHolder.set(agent);
        tools.createSubscription("4001", "5002", "", true);

        UserContextHolder.set(customer5001);
        // 不按姓名过滤，只按"本人"过滤：李四那单不应出现
        String result = tools.findSubscription("1幢101", null);

        assertThat(result).isEqualTo("未找到匹配的认购业务。");
    }

    @Test
    void agentFindSeesAllSubscriptions() {
        UserContextHolder.set(agent);
        tools.createSubscription("4001", "5002", "", true);

        String result = tools.findSubscription("1幢101", null);

        assertThat(result).contains("李四");
    }

    private String extractBizNo(String toolResult) {
        Matcher matcher = BIZ_NO.matcher(toolResult);
        assertThat(matcher.find()).as("工具结果里应包含业务编号：%s", toolResult).isTrue();
        return matcher.group(1).trim();
    }
}
