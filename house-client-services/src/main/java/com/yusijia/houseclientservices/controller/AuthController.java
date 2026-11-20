package com.yusijia.houseclientservices.controller;

import com.yusijia.houseclientservices.controller.dto.LoginRequest;
import com.yusijia.houseclientservices.controller.dto.LoginResponse;
import com.yusijia.houseclientservices.domain.entity.Conversation;
import com.yusijia.houseclientservices.security.AccessDeniedException;
import com.yusijia.houseclientservices.security.Authorization;
import com.yusijia.houseclientservices.security.PermissionPoints;
import com.yusijia.houseclientservices.service.AuthService;
import com.yusijia.houseclientservices.security.UserContext;
import com.yusijia.houseclientservices.service.ConversationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 登录 / 登出 / 会话列表（P0-1 假登录）。
 * <p>
 * SSE 不能用 EventSource 带 Authorization header，前端要么用 fetch + ReadableStream
 * 手动读 SSE，要么本控制器后续加一个 HttpOnly Cookie 模式。骨架先走 Bearer。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final ConversationService conversationService;
    private final long sessionTtlSeconds;

    public AuthController(AuthService authService,
                          ConversationService conversationService,
                          @Value("${agent.security.session-ttl-seconds:3600}") long sessionTtlSeconds) {
        this.authService = authService;
        this.conversationService = conversationService;
        this.sessionTtlSeconds = sessionTtlSeconds;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        String token = authService.login(request == null ? null : request.username(), sessionTtlSeconds);
        if (token == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new LoginResponse(null, "用户名不存在"));
        }
        UserContext user = authService.resolve(token);
        return ResponseEntity.ok(new LoginResponse(
                token,
                user.userId(),
                user.username(),
                user.role(),
                user.displayName(),
                "ok"));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            authService.logout(authorization.substring(7).trim());
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserContext> me(HttpServletRequest request) {
        UserContext user = Authorization.requireLogin(request);
        return ResponseEntity.ok(user);
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<Conversation>> conversations(HttpServletRequest request) {
        UserContext user = Authorization.requirePermission(request, PermissionPoints.CHAT_CONVERSATION);
        return ResponseEntity.ok(conversationService.listMine(user));
    }

}
