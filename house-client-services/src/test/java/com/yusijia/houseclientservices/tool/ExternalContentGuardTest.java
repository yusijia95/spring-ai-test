package com.yusijia.houseclientservices.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证"读过外部内容 → 本轮写工具熔断"的开关语义。
 * <p>
 * ThreadLocal 是复用的，所以每个用例结束都 clear，否则同一线程上的后续用例会被污染。
 */
class ExternalContentGuardTest {

    @AfterEach
    void 清理标记() {
        ExternalContentGuard.clear();
    }

    @Test
    void 未标记时写工具放行() {
        assertFalse(ExternalContentGuard.isSeen());
        assertNull(ExternalContentGuard.blockIfSeen("createSubscription"));
    }

    @Test
    void 标记后写工具返回拒绝文案() {
        ExternalContentGuard.markSeen();

        assertTrue(ExternalContentGuard.isSeen());
        String message = ExternalContentGuard.blockIfSeen("createSubscription");
        assertNotNull(message);
        assertTrue(message.contains("外部资料"), message);
        assertTrue(message.contains("未执行"), message);
        assertTrue(message.contains("确认"), message);
    }

    @Test
    void 不同写工具给出对应操作名() {
        ExternalContentGuard.markSeen();

        String create = ExternalContentGuard.blockIfSeen("createSubscription");
        String cancel = ExternalContentGuard.blockIfSeen("cancelSubscription");
        assertTrue(create.contains("认购办理"), create);
        assertTrue(cancel.contains("认购撤销"), cancel);
    }

    @Test
    void 清理后恢复放行() {
        ExternalContentGuard.markSeen();
        ExternalContentGuard.clear();

        assertFalse(ExternalContentGuard.isSeen());
        assertNull(ExternalContentGuard.blockIfSeen("cancelSubscription"));
    }
}
