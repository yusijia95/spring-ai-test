package com.yusijia.houseclientservices.service;

import com.yusijia.houseclientservices.domain.audit.ToolCallAudit;
import com.yusijia.houseclientservices.domain.entity.AgentAudit;
import com.yusijia.houseclientservices.mapper.AgentAuditMapper;
import com.yusijia.houseclientservices.security.UserContext;
import com.yusijia.houseclientservices.tool.ToolExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-5 审计落表的纯逻辑测试（mock mapper，不依赖数据库）：
 * turn 自增、args/result 脱敏、写失败不抛（旁路）。
 */
class AgentAuditServiceTest {

    private final UserContext agent = new UserContext(2L, "agent1", UserContext.ROLE_AGENT, "演示坐席1");
    private final AgentAuditMapper mapper = mock(AgentAuditMapper.class);
    private final AgentAuditService service = new AgentAuditService(mapper);

    private final ToolExecutionContext exec = new ToolExecutionContext(agent, "verify-audit-cid");
    private AgentAudit captured;

    @BeforeEach
    void captureInsert() {
        captured = null;
        when(mapper.insert(any(AgentAudit.class))).thenAnswer(inv -> {
            captured = inv.getArgument(0);
            return 1;
        });
    }

    @Test
    void turnIncrementsPerConversation() {
        when(mapper.maxTurn("verify-audit-cid")).thenReturn(3);

        exec.recordToolCall(new ToolCallAudit("searchHouse",
                "{\"keyword\":\"文一西路\"}", "ID=4001，坐落=…", 12, false));
        service.record(agent, "verify-audit-cid", List.of("subscription"), exec, "qwen-max", 1500);

        verify(mapper).maxTurn("verify-audit-cid");
        assertThat(captured).isNotNull();
        assertThat(captured.getTurn()).isEqualTo(4);
        assertThat(captured.getCid()).isEqualTo("verify-audit-cid");
        assertThat(captured.getUserId()).isEqualTo(2L);
        assertThat(captured.getSkills()).isEqualTo("subscription");
        assertThat(captured.getTool()).isEqualTo("searchHouse");
        assertThat(captured.getModel()).isEqualTo("qwen-max");
        assertThat(captured.getLatencyMs()).isEqualTo(1500);
        assertThat(captured.getConfirmed()).isFalse();
    }

    @Test
    void noToolCallLeavesToolAndArgsNull() {
        when(mapper.maxTurn("verify-audit-cid")).thenReturn(0);

        service.record(agent, "verify-audit-cid", List.of(), exec, "qwen-max", 800);

        assertThat(captured).isNotNull();
        assertThat(captured.getTurn()).isEqualTo(1);
        assertThat(captured.getTool()).isEmpty();
        assertThat(captured.getArgs()).isNull();
        assertThat(captured.getResult()).isNull();
    }

    @Test
    void argsAndResultAreMaskedBeforePersist() {
        when(mapper.maxTurn("verify-audit-cid")).thenReturn(0);
        exec.recordToolCall(new ToolCallAudit("searchPerson",
                "{\"keyword\":\"张三 330106199001011234\"}",
                "ID=5001，姓名=张三，证件号码=330106199001011234，电话=13800000001",
                30, false));

        service.record(agent, "verify-audit-cid", List.of("subscription"), exec, "qwen-max", 900);

        assertThat(captured.getArgs()).contains("330106199001011234".substring(0, 6) + "********1234");
        assertThat(captured.getArgs()).doesNotContain("330106199001011234");
        assertThat(captured.getResult()).contains("138****0001");
        assertThat(captured.getResult()).doesNotContain("13800000001");
    }

    @Test
    void confirmedReflectsWriteConfirmation() {
        when(mapper.maxTurn("verify-audit-cid")).thenReturn(0);
        exec.markWriteConfirmed();

        service.record(agent, "verify-audit-cid", List.of("subscription"), exec, "qwen-max", 900);

        assertThat(captured.getConfirmed()).isTrue();
    }

    @Test
    void writeFailureDoesNotThrow() {
        when(mapper.maxTurn(anyString())).thenThrow(new RuntimeException("db down"));

        assertThatNoException().isThrownBy(() ->
                service.record(agent, "verify-audit-cid", List.of(), exec, "qwen-max", 900));
    }

    @Test
    void nullUserIsSkipped() {
        assertThatNoException().isThrownBy(() ->
                service.record(null, "verify-audit-cid", List.of(), exec, "qwen-max", 900));
        verify(mapper, org.mockito.Mockito.never()).insert(any(AgentAudit.class));
    }

    @Test
    void nullExecRecordsWithoutCalls() {
        when(mapper.maxTurn("verify-audit-cid")).thenReturn(0);

        service.record(agent, "verify-audit-cid", List.of(), null, "qwen-max", 900);

        assertThat(captured).isNotNull();
        assertThat(captured.getConfirmed()).isFalse();
        assertThat(captured.getTool()).isEmpty();
    }
}
