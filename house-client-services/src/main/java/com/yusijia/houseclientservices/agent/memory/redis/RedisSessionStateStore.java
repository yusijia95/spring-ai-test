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
 * Redis Hash {@code session:{userId}:{cid}}：一个会话一个 Hash，字段可单独更新，
 * 键名带哈希标签便于将来上 Redis Cluster。
 * A2 起 key 带 userId 命名空间，按用户清理和审计更方便。
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
    public Map<String, String> get(String userId, String conversationId) {
        String localKey = localKey(conversationId);
        try {
            Map<String, String> state = redis.hgetAll(key(userId, conversationId));
            return state == null ? Map.of() : new LinkedHashMap<>(state);
        } catch (RuntimeException e) {
            log.warn("读取会话状态失败，使用进程内副本: {}", e.getMessage());
            return new LinkedHashMap<>(localFallback.getOrDefault(localKey, Map.of()));
        }
    }

    @Override
    public void put(String userId, String conversationId, String field, String value) {
        if (!ALLOWED_KEYS.contains(field)) {
            throw new IllegalArgumentException("不支持的会话状态字段: " + field);
        }
        String localKey = localKey(conversationId);
        String redisKey = key(userId, conversationId);
        try {
            redis.hset(redisKey, Map.of(field, value));
            redis.expire(redisKey, ttlSeconds);
        } catch (RuntimeException e) {
            log.warn("写入会话状态失败，暂存进程内: {}", e.getMessage());
            localFallback.computeIfAbsent(localKey, k -> new ConcurrentHashMap<>()).put(field, value);
            return;
        }
        localFallback.remove(localKey);
    }

    @Override
    public void clear(String userId, String conversationId) {
        localFallback.remove(localKey(conversationId));
        try {
            redis.del(key(userId, conversationId));
        } catch (RuntimeException e) {
            log.warn("清理会话状态失败: {}", e.getMessage());
        }
    }

    /**
     * 构建带 userId 命名空间的 Redis key（A2）。
     * 格式：{@code session:{userId}:{cid}}，其中 {@code {cid}} 是哈希标签。
     * userId 缺失时退化为旧格式 {@code session:{cid}}。
     */
    private static String key(String userId, String conversationId) {
        if (userId == null || userId.isBlank()) {
            return "session:{" + conversationId + "}";
        }
        return "session:{" + userId + ":" + conversationId + "}";
    }

    /** 进程内兜底 Map 的 key（不需要 userId，因为进程内只有一份） */
    private static String localKey(String conversationId) {
        return conversationId;
    }
}
