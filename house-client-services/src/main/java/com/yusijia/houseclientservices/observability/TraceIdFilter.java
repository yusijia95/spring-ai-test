package com.yusijia.houseclientservices.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 每个 HTTP 请求绑定一个 traceId 到 MDC，所有日志行自动携带。
 * <p>
 * 优先从请求头 {@code X-Trace-Id} 继承（多实例 + LB 场景下，网关/前端可能已分配）；
 * 缺省时生成 16 位 hex。响应头 {@code X-Trace-Id} 回传给前端，方便报障时定位。
 */
@Component
@Order(1)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        if (incoming != null && !incoming.isBlank()) {
            MDC.put(TraceContext.TRACE_ID, incoming);
        } else {
            String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            MDC.put(TraceContext.TRACE_ID, id);
        }
        response.setHeader(HEADER, MDC.get(TraceContext.TRACE_ID));
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TraceContext.TRACE_ID);
        }
    }
}
