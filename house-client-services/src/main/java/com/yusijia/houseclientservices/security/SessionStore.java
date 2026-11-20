package com.yusijia.houseclientservices.security;

/**
 * 会话令牌存储（P0-1）。
 * <p>
 * 登录接口签发不透明 token，这里存 token -> {@link UserContext} 的映射。
 * 多实例 + 负载均衡下必须用共享存储（Redis），不能放进程内 Map，
 * 否则 LB 把同一用户的两次请求打到不同实例时，后一次会"查不到会话"。
 * <p>
 * 所有方法在存储不可用时抛 {@link RuntimeException}，由调用方决定降级。
 */
public interface SessionStore {

    /** 写入会话，TTL 秒。 */
    void put(String token, UserContext user, long ttlSeconds);

    /** 读取；不存在或已过期返回 null。 */
    UserContext get(String token);

    /** 注销（登出 / 改密时调用）。 */
    void remove(String token);
}
