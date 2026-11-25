package com.yusijia.houseclientservices.tool;

import com.yusijia.houseclientservices.domain.view.PersonView;
import com.yusijia.houseclientservices.security.UserContext;
import com.yusijia.houseclientservices.service.MasterDataService;
import com.yusijia.houseclientservices.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 AI 工具侧的手机号可见性：按 {@code data:person:phone:read} 决定明文还是掩码，
 * 上下文缺失时 fail-closed（掩码）。
 */
class SubscriptionToolsPhoneTest {

    private static final UserContext CUSTOMER =
            new UserContext(1L, "customer1", UserContext.ROLE_CUSTOMER, "演示客户1", 5001L);
    private static final UserContext AGENT =
            new UserContext(2L, "agent1", UserContext.ROLE_AGENT, "演示坐席1");

    private final MasterDataService masterDataService = mock(MasterDataService.class);
    private final SubscriptionTools tools =
            new SubscriptionTools(masterDataService, mock(SubscriptionService.class));

    @Test
    void 客户查询客户时手机号掩码() {
        when(masterDataService.searchPersons("李四", false))
                .thenReturn(List.of(new PersonView("5002", "李四", "3301**********2345", "138****0002")));

        String output = tools.searchPerson("李四", context(CUSTOMER));

        assertThat(output).contains("138****0002").doesNotContain("13800000002");
        verify(masterDataService).searchPersons("李四", false);
    }

    @Test
    void 坐席查询客户时手机号明文() {
        when(masterDataService.searchPersons("李四", true))
                .thenReturn(List.of(new PersonView("5002", "李四", "3301**********2345", "13800000002")));

        String output = tools.searchPerson("李四", context(AGENT));

        assertThat(output).contains("13800000002");
        verify(masterDataService).searchPersons("李四", true);
    }

    @Test
    void 工具上下文缺失时按掩码处理() {
        when(masterDataService.searchPersons("李四", false))
                .thenReturn(List.of(new PersonView("5002", "李四", "3301**********2345", "138****0002")));

        String output = tools.searchPerson("李四", new ToolContext(Map.of()));

        assertThat(output).contains("138****0002").doesNotContain("13800000002");
        verify(masterDataService).searchPersons("李四", false);
    }

    private static ToolContext context(UserContext user) {
        return new ToolContext(Map.of(
                ToolExecutionContext.KEY, new ToolExecutionContext(user, "verify-phone")));
    }
}
