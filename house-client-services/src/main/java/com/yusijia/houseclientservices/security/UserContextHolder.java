package com.yusijia.houseclientservices.security;

/**
 * 当前请求的 {@link UserContext} 持有者（ThreadLocal）。
 * <p>
 * AI 工具（{@code @Tool} 方法）由模型在 {@code ChatClient.call()} 内<b>同步</b>执行，
 * 跑在发起请求的同一线程上，所以 ThreadLocal 能把登录用户带进工具——这与
 * {@code SessionStateTools.bind} 用的是同一套约定。
 * <p>
 * 使用约束：必须在请求线程里 {@link #set}，并在 {@code finally} 里 {@link #clear}，
 * 否则线程池复用会造成"上一个客户的上下文泄漏给下一个客户"。工具内读不到
 * （null）时按"未登录"处理，不做任何越权放行。
 */
public final class UserContextHolder {

    private static final ThreadLocal<UserContext> CURRENT = new ThreadLocal<>();

    private UserContextHolder() {
    }

    public static void set(UserContext user) {
        if (user == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(user);
        }
    }

    /** 当前请求的登录用户；未绑定（如定时任务、非对话链路）返回 null。 */
    public static UserContext get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
