package com.yusijia.houseclientservices.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 从 {@code Authorization: Bearer <token>} 解析出 {@link UserContext}，
 * 放进 request attribute（供控制器读取）和 MDC（供日志 grep）。
 * <p>
 * 不做拦截：本 Filter 只负责"有 token 就解析"。是否允许访问由控制器里
 * 显式的 {@code requireLogin}/{@code requirePermission} 决定——骨架阶段
 * 这样最透明，哪些接口需要登录一眼可见。
 * <p>
 * Order 在 {@code TraceIdFilter}(1) 之后，保证 MDC 里已有 traceId。
 */
@Component
@Order(2)
public class UserContextFilter extends OncePerRequestFilter {

    public static final String ATTR_USER = "agent.userContext";
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;

    public UserContextFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = extractToken(request);
        UserContext user = null;
        if (token != null) {
            user = authService.resolve(token);
        }
        if (user != null) {
            request.setAttribute(ATTR_USER, user);
            MDC.put("userId", String.valueOf(user.userId()));
            MDC.put("username", user.username());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("userId");
            MDC.remove("username");
        }
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        return null;
    }

    /** 控制器辅助：从 request 取当前用户，未登录返回 null。 */
    public static UserContext fromRequest(HttpServletRequest request) {
        Object attr = request.getAttribute(ATTR_USER);
        return attr instanceof UserContext uc ? uc : null;
    }
}
