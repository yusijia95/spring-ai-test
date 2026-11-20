package com.yusijia.houseclientservices.service;

import com.yusijia.houseclientservices.security.DemoAccounts;
import com.yusijia.houseclientservices.security.SessionStore;
import com.yusijia.houseclientservices.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 登录 / 登出（P0-1 假登录）。
 * <p>
 * 只校验用户名是否在 {@link DemoAccounts} 白名单里，命中即签发不透明令牌。
 * 令牌是 256-bit 随机串（Base64URL），服务端存 token -> {@link UserContext}，
 * 客户端只持有 token，不持有角色（角色以服务端存储为准，防止前端伪造角色提权）。
 * <p>
 * 按项目规范放 {@code service/} 包（业务服务），安全机制（Filter/判定/值对象）留在 {@code security/}。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final SessionStore sessionStore;

    private final SecureRandom random = new SecureRandom();

    public AuthService(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    /**
     * 登录：白名单命中则签发令牌。
     *
     * @return 令牌；用户不存在时返回 null（控制器据此返回 401）
     */
    public String login(String username, long ttlSeconds) {
        UserContext user = DemoAccounts.find(username);
        if (user == null) {
            log.info("auth: 登录失败，未知用户 username={}", username);
            return null;
        }
        String token = newToken();
        sessionStore.put(token, user, ttlSeconds);
        log.info("auth: 登录成功 userId={} role={} username={}", user.userId(), user.role(), user.username());
        return token;
    }

    /** 登出：删除令牌（幂等）。 */
    public void logout(String token) {
        if (StringUtils.hasText(token)) {
            sessionStore.remove(token);
        }
    }

    /** 由令牌查用户；无 / 过期返回 null。 */
    public UserContext resolve(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        return sessionStore.get(token);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
