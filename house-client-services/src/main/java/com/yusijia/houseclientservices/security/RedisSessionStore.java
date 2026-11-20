package com.yusijia.houseclientservices.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yusijia.houseclientservices.agent.memory.redis.RedisOperations;

/**
 * Redis 实现：token 作为 key，value 是 {@link UserContext} 的 JSON，带 TTL。
 * <p>
 * 复用记忆层的 {@link RedisOperations}（不直接依赖 Jedis），
 * 这样测试可以塞一个内存实现，不需要真的起 Redis。
 */
public class RedisSessionStore implements SessionStore {

    private static final String KEY_PREFIX = "auth:session:";

    private final RedisOperations redis;
    private final ObjectMapper mapper = new ObjectMapper();

    public RedisSessionStore(RedisOperations redis) {
        this.redis = redis;
    }

    @Override
    public void put(String token, UserContext user, long ttlSeconds) {
        try {
            String json = mapper.writeValueAsString(user);
            redis.setex(KEY_PREFIX + token, ttlSeconds, json);
        } catch (JsonProcessingException e) {
            // UserContext 是简单 record，序列化不会失败；真失败说明有 bug
            throw new IllegalStateException("序列化会话上下文失败", e);
        }
    }

    @Override
    public UserContext get(String token) {
        String json = redis.get(KEY_PREFIX + token);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, UserContext.class);
        } catch (JsonProcessingException e) {
            // 值损坏：当作无会话，让上层按未登录处理（401），不抛 500
            return null;
        }
    }

    @Override
    public void remove(String token) {
        redis.del(KEY_PREFIX + token);
    }
}
