package com.yusijia.houseclientservices.config;

import com.yusijia.houseclientservices.agent.memory.redis.RedisOperations;
import com.yusijia.houseclientservices.security.RedisSessionStore;
import com.yusijia.houseclientservices.security.SessionStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 安全层装配（P0-1）。
 * <p>
 * SessionStore 用 Redis 实现（复用记忆层 JedisPool），多实例共享会话。
 * Redis 不可用时登录会抛异常——骨架阶段不做"无 Redis 也能登录"的降级，
 * 因为会话令牌必须跨实例可用，进程内 Map 会破坏 LB 下的登录态。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SessionStore sessionStore(RedisOperations redisOperations) {
        return new RedisSessionStore(redisOperations);
    }
}
