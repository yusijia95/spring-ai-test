package com.yusijia.houseclientservices.agent;

import com.yusijia.houseclientservices.domain.audit.ToolCallAudit;
import com.yusijia.houseclientservices.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0-5 审计装饰器（ToolRegistry.audit）的纯逻辑测试：
 * 每次调用被记入本轮上下文（含耗时）、异常原样上抛且标记 failed、上下文缺失不记录也不炸。
 */
class ToolAuditDecoratorTest {

    private static ToolContext ctx(ToolExecutionContext exec) {
        return new ToolContext(Map.of(ToolExecutionContext.KEY, exec));
    }

    private static ToolExecutionContext exec() {
        return new ToolExecutionContext(null, "verify-audit-decorator");
    }

    @Test
    void 每次调用都被记录且成功时failed为false() {
        ToolCallback audited = ToolRegistry.audit(fakeCallback("searchHouse", false));
        ToolExecutionContext exec = exec();

        String result = audited.call("{\"keyword\":\"文一西路\"}", ctx(exec));

        assertThat(result).isEqualTo("ok");
        List<ToolCallAudit> calls = exec.auditCalls();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).tool()).isEqualTo("searchHouse");
        assertThat(calls.get(0).args()).isEqualTo("{\"keyword\":\"文一西路\"}");
        assertThat(calls.get(0).result()).isEqualTo("ok");
        assertThat(calls.get(0).failed()).isFalse();
        assertThat(calls.get(0).durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void 多次调用按顺序追加() {
        ToolCallback audited = ToolRegistry.audit(fakeCallback("searchHouse", false));
        ToolExecutionContext exec = exec();

        audited.call("a", ctx(exec));
        audited.call("b", ctx(exec));

        assertThat(exec.auditCalls()).hasSize(2);
        assertThat(exec.auditCalls().get(0).args()).isEqualTo("a");
        assertThat(exec.auditCalls().get(1).args()).isEqualTo("b");
    }

    @Test
    void 异常原样上抛且记录failed() {
        ToolCallback audited = ToolRegistry.audit(boomCallback("searchHouse"));
        ToolExecutionContext exec = exec();

        assertThatThrownBy(() -> audited.call("{}", ctx(exec)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");

        assertThat(exec.auditCalls()).hasSize(1);
        assertThat(exec.auditCalls().get(0).failed()).isTrue();
        assertThat(exec.auditCalls().get(0).result()).contains("boom");
    }

    @Test
    void 上下文缺失时不记录也不影响调用() {
        ToolCallback audited = ToolRegistry.audit(fakeCallback("searchHouse", false));

        String result = audited.call("{}", new ToolContext(Map.of()));

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void 无上下文重载也能正常调用() {
        ToolCallback audited = ToolRegistry.audit(fakeCallback("searchHouse", false));

        assertThat(audited.call("{}")).isEqualTo("ok");
    }

    private static ToolCallback fakeCallback(String name, boolean executedFlag) {
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description("测试用工具")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                executed.set(true);
                return "ok";
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                executed.set(true);
                return "ok";
            }
        };
    }

    private static ToolCallback boomCallback(String name) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description("测试用异常工具")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                throw new IllegalStateException("boom");
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                throw new IllegalStateException("boom");
            }
        };
    }
}
