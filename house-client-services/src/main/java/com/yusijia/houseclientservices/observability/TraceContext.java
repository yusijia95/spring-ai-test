package com.yusijia.houseclientservices.observability;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 请求级 traceId 管理。
 * <p>
 * 用 SLF4J MDC 传递，所有 log 行自动带上 [traceId=xxx]（配合 logback pattern）。
 * 每个 HTTP 请求一个 traceId，从 Filter 入口 set，请求结束 clear。
 * <p>
 * 为什么不用 Sleuth/Brave：本地仓库没有对应版本，且项目不引 actuator，
 * 一个 MDC key 就够用——多实例 + LB 场景下，前端拿不到跨服务 trace，
 * traceId 的价值是"同一请求的 N 行日志能 grep 到一起"。
 */
public final class TraceContext {

    public static final String TRACE_ID = "traceId";

    private TraceContext() {
    }

    /** 生成并绑定新 traceId，返回该 ID（可用于响应头回传给前端）。 */
    public static String start() {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put(TRACE_ID, id);
        return id;
    }

    /** 沿用已有 traceId（例如从请求头继承）。 */
    public static void bind(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(TRACE_ID, traceId);
        } else {
            start();
        }
    }

    public static String current() {
        return MDC.get(TRACE_ID);
    }

    /** 请求结束清理，防止线程复用串号。 */
    public static void clear() {
        MDC.remove(TRACE_ID);
    }
}
