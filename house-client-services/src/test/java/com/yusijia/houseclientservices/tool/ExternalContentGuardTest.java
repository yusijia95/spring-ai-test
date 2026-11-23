package com.yusijia.houseclientservices.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证"读过外部内容 → 本轮写工具熔断"的开关语义。
 * <p>
 * 状态挂在每请求一个的 {@link ToolExecutionContext} 上，所以用例之间天然隔离，
 * 不需要清理（这也正是从 ThreadLocal 迁出来的原因之一）。
 */
class ExternalContentGuardTest {

    private static ToolExecutionContext 新上下文() {
        return new ToolExecutionContext(null, "test-guard");
    }

    @Test
    void 未标记时写工具放行() {
        assertNull(ExternalContentGuard.blockIfSeen(新上下文(), "createSubscription"));
    }

    @Test
    void 标记后写工具返回拒绝文案() {
        ToolExecutionContext exec = 新上下文();
        exec.markExternalContentSeen();

        String message = ExternalContentGuard.blockIfSeen(exec, "createSubscription");
        assertNotNull(message);
        assertTrue(message.contains("外部资料"), message);
        assertTrue(message.contains("未执行"), message);
        assertTrue(message.contains("确认"), message);
    }

    @Test
    void 不同写工具给出对应操作名() {
        ToolExecutionContext exec = 新上下文();
        exec.markExternalContentSeen();

        String create = ExternalContentGuard.blockIfSeen(exec, "createSubscription");
        String cancel = ExternalContentGuard.blockIfSeen(exec, "cancelSubscription");
        assertTrue(create.contains("认购办理"), create);
        assertTrue(cancel.contains("认购撤销"), cancel);
    }

    @Test
    void 上下文缺失时保守拒绝() {
        // 装配错误（工具上下文没传进来）绝不能静默放行写操作
        String message = ExternalContentGuard.blockIfSeen(null, "cancelSubscription");
        assertNotNull(message);
        assertTrue(message.contains("无法确认上下文"), message);
    }

    @Test
    void 新一次请求默认放行() {
        ToolExecutionContext first = 新上下文();
        first.markExternalContentSeen();

        // 模拟下一轮请求：新实例，标记不继承
        assertNull(ExternalContentGuard.blockIfSeen(新上下文(), "cancelSubscription"));
    }
}
