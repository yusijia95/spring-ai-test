package com.yusijia.houseclientservices.tool;

import com.yusijia.houseclientservices.domain.audit.ToolCallAudit;
import com.yusijia.houseclientservices.security.UserContext;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次 agent 请求的工具执行上下文，随请求走，<b>不依赖线程</b>。
 * <p>
 * 为什么不用 ThreadLocal：控制器返回 {@code Flux<String>}，一旦工具在响应式操作符
 * 之后（或 {@code .flatMap} / 并行调度 / 虚拟线程）执行，ThreadLocal 就换线程了；
 * 更糟的是它<b>静默失效</b>——工具读不到用户时会当成"无限制"放行，是 fail-open。
 * Spring AI 的 {@code ToolContext} 由调用方显式随请求传入（{@code prompt().toolContext(...)}），
 * 在同步与流式路径上都成立，这是官方机制。
 * <p>
 * 一个实例承载本轮所有可变状态：
 * <ul>
 *   <li>{@code user}：登录用户（自助客户的数据范围校验靠它）</li>
 *   <li>{@code conversationId}：会话事实写入的键</li>
 *   <li>{@code externalContentSeen}：本轮是否读过外部不可信内容（写工具熔断依据）</li>
 *   <li>{@code createSubscriptionCalled}：本轮是否真的创建过认购（防模型幻觉）</li>
 *   <li>{@code lastGeocodeLocation}：最近一次地理编码坐标（纠正百度地图工具误用）</li>
 *   <li>{@code auditCalls}：本轮所有工具调用（P0-5 审计），由 ToolRegistry 的审计装饰器追加</li>
 *   <li>{@code writeConfirmed}：本轮写操作是否经客户二次确认（P0-4 → P0-5 confirmed 字段）</li>
 * </ul>
 * 字段用 Atomic* 是因为同一次请求内工具可能被框架并行调用；跨请求互不影响。
 */
public final class ToolExecutionContext {

    /** 在 {@link ToolContext#getContext()} 里的键 */
    public static final String KEY = "agent.toolExec";

    private final UserContext user;
    private final String conversationId;

    private final AtomicBoolean externalContentSeen = new AtomicBoolean(false);
    private final AtomicBoolean createSubscriptionCalled = new AtomicBoolean(false);
    private final AtomicReference<String> lastGeocodeLocation = new AtomicReference<>();
    private final AtomicBoolean projectSearchCalled = new AtomicBoolean(false);
    private final AtomicReference<String> projectNotFoundFact = new AtomicReference<>();
    private final AtomicBoolean writeConfirmed = new AtomicBoolean(false);
    /** 本轮所有工具调用（P0-5 审计），按调用完成顺序追加；同步写，供请求结束后一次性落表。 */
    private final List<ToolCallAudit> auditCalls = Collections.synchronizedList(new java.util.ArrayList<>());

    public ToolExecutionContext(UserContext user, String conversationId) {
        this.user = user;
        this.conversationId = conversationId;
    }

    /**
     * 从框架注入的 {@link ToolContext} 取出本轮上下文。
     *
     * @return 取不到返回 null（调用方必须按 fail-closed 处理，绝不能当成"无限制"）
     */
    public static ToolExecutionContext of(ToolContext toolContext) {
        if (toolContext == null) {
            return null;
        }
        Map<String, Object> context = toolContext.getContext();
        if (context == null || context.isEmpty()) {
            return null;
        }
        Object value = context.get(KEY);
        return value instanceof ToolExecutionContext exec ? exec : null;
    }

    public UserContext user() {
        return user;
    }

    public String conversationId() {
        return conversationId;
    }

    public void markExternalContentSeen() {
        externalContentSeen.set(true);
    }

    public boolean externalContentSeen() {
        return externalContentSeen.get();
    }

    public void markCreateSubscriptionCalled() {
        createSubscriptionCalled.set(true);
    }

    public boolean createSubscriptionCalled() {
        return createSubscriptionCalled.get();
    }

    public void rememberGeocodeLocation(String location) {
        if (location != null && !location.isBlank()) {
            lastGeocodeLocation.set(location);
        }
    }

    public String lastGeocodeLocation() {
        return lastGeocodeLocation.get();
    }

    public void markProjectSearchCalled() {
        projectSearchCalled.set(true);
    }

    public boolean projectSearchCalled() {
        return projectSearchCalled.get();
    }

    /** 查不到楼盘时可转述的事实行 */
    public void rememberProjectNotFoundFact(String fact) {
        projectNotFoundFact.set(fact);
    }

    public String projectNotFoundFact() {
        return projectNotFoundFact.get();
    }

    /** 记一次工具调用（P0-5 审计），由 ToolRegistry 的审计装饰器统一调用。 */
    public void recordToolCall(ToolCallAudit call) {
        if (call != null) {
            auditCalls.add(call);
        }
    }

    /** 本轮工具调用审计快照（只读拷贝，请求结束后落表用）。 */
    public List<ToolCallAudit> auditCalls() {
        synchronized (auditCalls) {
            return List.copyOf(auditCalls);
        }
    }

    /**
     * 标记本轮写操作已获客户二次确认（P0-4）。
     * 只有真正执行了创建/撤销（confirmed=true 且成功）才由写工具标记；
     * 预览（confirmed=false）、被熔断、被数据范围拦截都不算。
     */
    public void markWriteConfirmed() {
        writeConfirmed.set(true);
    }

    public boolean writeConfirmed() {
        return writeConfirmed.get();
    }
}
