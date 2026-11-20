package com.yusijia.houseclientservices.agent.memory.redis;

import com.yusijia.houseclientservices.agent.memory.SessionStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话状态的 Redis 实现。
 * <p>
 * Redis Hash {@code session:{cid}}：一个会话一个 Hash，字段可单独更新，
 * 键名带哈希标签便于将来上 Redis Cluster。
 * <p>
 * Redis 不可用时退化为进程内 Map：单实例仍然可用，多实例下会退化成"各实例各一份"，
 * 这是有意的取舍——状态丢失最多让模型多问一句，比整轮报错好。
 */
public class RedisSessionStateStore implements SessionStateStore {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionStateStore.class);

    private final RedisOperations redis;
    private final long ttlSeconds;
    private final Map<String, Map<String, String>> localFallback = new ConcurrentHashMap<>();

    public RedisSessionStateStore(RedisOperations redis, long ttlSeconds) {
        this.redis = redis;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public Map<String, String> get(String conversationId) {
        try {
            Map<String, String> state = redis.hgetAll(key(conversationId));
            return state == null ? Map.of() : new LinkedHashMap<>(state);
        } catch (RuntimeException e) {
            log.warn("读取会话状态失败，使用进程内副本: {}", e.getMessage());
            return new LinkedHashMap<>(localFallback.getOrDefault(conversationId, Map.of()));
        }
    }

    @Override
    public void put(String conversationId, String field, String value) {
        if (!ALLOWED_KEYS.contains(field)) {
            throw new IllegalArgumentException("不支持的会话状态字段: " + field);
        }
        try {
            redis.hset(key(conversationId), Map.of(field, value));
            redis.expire(key(conversationId), ttlSeconds);
        } catch (RuntimeException e) {
            log.warn("写入会话状态失败，暂存进程内: {}", e.getMessage());
            localFallback.computeIfAbsent(conversationId, k -> new ConcurrentHashMap<>()).put(field, value);
            return;
        }
        localFallback.remove(conversationId);
    }

    @Override
    public void clear(String conversationId) {
        localFallback.remove(conversationId);
        try {
            redis.del(key(conversationId));
        } catch (RuntimeException e) {
            log.warn("清理会话状态失败: {}", e.getMessage());
        }
    }

    private static String key(String conversationId) {
        return "session:{" + conversationId + "}";
    }
}
