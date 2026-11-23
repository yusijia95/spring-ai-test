package com.yusijia.houseclientservices.agent;

import com.yusijia.houseclientservices.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证写工具包装层：读过外部资料后，工具不该被真正执行，
 * 而是把拒绝文案当作返回值交回模型（不抛异常，否则客户只会拿到一个报错）。
 * <p>
 * 上下文通过 {@link ToolContext} 显式传入，与真实调用链一致；包装层还必须把上下文
 * <b>转发</b>给内层回调，否则带 ToolContext 参数的工具会因"上下文为空"被框架拒绝。
 */
class WriteToolGuardTest {

    private static ToolContext ctx(ToolExecutionContext exec) {
        return new ToolContext(Map.of(ToolExecutionContext.KEY, exec));
    }

    private static ToolExecutionContext exec() {
        return new ToolExecutionContext(null, "test-write-guard");
    }

    @Test
    void 未读外部资料时正常执行() {
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolCallback guarded = ToolRegistry.guardWriteTool(fakeCallback("createSubscription", executed));

        String result = guarded.call("{}", ctx(exec()));

        assertTrue(executed.get(), "标记未设置时工具应真正执行");
        assertEquals("ok", result);
    }

    @Test
    void 读过外部资料时返回拒绝文案且不执行() {
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolCallback guarded = ToolRegistry.guardWriteTool(fakeCallback("createSubscription", executed));
        ToolExecutionContext seen = exec();
        seen.markExternalContentSeen();

        String result = guarded.call("{}", ctx(seen));

        assertFalse(executed.get(), "熔断时不得真正落库");
        assertNotNull(result);
        assertTrue(result.contains("外部资料"), result);
        assertTrue(result.contains("未执行"), result);
    }

    @Test
    void 下一轮请求重新放行() {
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolCallback guarded = ToolRegistry.guardWriteTool(fakeCallback("cancelSubscription", executed));
        ToolExecutionContext seen = exec();
        seen.markExternalContentSeen();
        guarded.call("{}", ctx(seen));

        // 新请求 = 新上下文实例，标记不继承
        assertEquals("ok", guarded.call("{}", ctx(exec())));
        assertTrue(executed.get());
    }

    @Test
    void 上下文缺失时保守拒绝() {
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolCallback guarded = ToolRegistry.guardWriteTool(fakeCallback("cancelSubscription", executed));

        String result = guarded.call("{}", new ToolContext(Map.of()));

        assertFalse(executed.get(), "没有上下文时不得执行写操作");
        assertTrue(result.contains("无法确认上下文"), result);
    }

    @Test
    void 上下文被原样转发给内层工具() {
        ToolExecutionContext exec = exec();
        ToolCallback inner = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("createSubscription")
                        .description("测试用写工具")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                throw new AssertionError("不该走无上下文的重载");
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                // 不转发的话这里会看到 null，真实链路下 MethodToolCallback 就会报错
                return ToolExecutionContext.of(toolContext) == exec ? "ok" : "lost";
            }
        };

        assertEquals("ok", ToolRegistry.guardWriteTool(inner).call("{}", ctx(exec)));
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
