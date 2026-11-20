package com.yusijia.houseclientservices.agent;

import com.yusijia.houseclientservices.tool.ExternalContentGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证写工具包装层：读过外部资料后，工具不该被真正执行，
 * 而是把拒绝文案当作返回值交回模型（不抛异常，否则客户只会拿到一个报错）。
 */
class WriteToolGuardTest {

    @AfterEach
    void 清理标记() {
        ExternalContentGuard.clear();
    }

    @Test
    void 未读外部资料时正常执行() {
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolCallback guarded = ToolRegistry.guardWriteTool(fakeCallback("createSubscription", executed));

        String result = guarded.call("{}");

        assertTrue(executed.get(), "标记未设置时工具应真正执行");
        assertEquals("ok", result);
    }

    @Test
    void 读过外部资料时返回拒绝文案且不执行() {
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolCallback guarded = ToolRegistry.guardWriteTool(fakeCallback("createSubscription", executed));
        ExternalContentGuard.markSeen();

        String result = guarded.call("{}");

        assertFalse(executed.get(), "熔断时不得真正落库");
        assertNotNull(result);
        assertTrue(result.contains("外部资料"), result);
        assertTrue(result.contains("未执行"), result);
    }

    @Test
    void 清理后重新放行() {
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolCallback guarded = ToolRegistry.guardWriteTool(fakeCallback("cancelSubscription", executed));
        ExternalContentGuard.markSeen();
        guarded.call("{}");
        ExternalContentGuard.clear();

        assertEquals("ok", guarded.call("{}"));
        assertTrue(executed.get());
    }

    /** 伪造一个能被追踪"是否真被执行"的工具 */
    private static ToolCallback fakeCallback(String name, AtomicBoolean executed) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description("测试用写工具")
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
        };
    }
}
