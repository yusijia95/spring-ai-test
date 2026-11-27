package com.yusijia.houseclientservices.agent.memory;

import com.yusijia.houseclientservices.agent.memory.redis.RedisOperations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 RedisOperations：单测用，不依赖真实 Redis。
 * <p>
 * 只实现记忆层用到的语义：LIST（RPUSH / LRANGE / LTRIM）、STRING（GET / SETEX / SET NX PX）、
 * HASH（HSET / HGETALL / HDEL）。TTL 不模拟过期，只记录最后一次设置的值。
 * <p>
 * {@link #failure} 非空时所有命令抛异常，用来验证降级路径。
 */
public class FakeRedisOperations implements RedisOperations {

    public final Map<String, List<String>> lists = new ConcurrentHashMap<>();
    public final Map<String, String> strings = new ConcurrentHashMap<>();
    public final Map<String, Map<String, String>> hashes = new ConcurrentHashMap<>();
    public final List<String> lockedKeys = new ArrayList<>();

    /** 非空则该异常从每个命令抛出，模拟 Redis 不可用 */
    public RuntimeException failure;

    private void check() {
        if (failure != null) {
            throw new RuntimeException(failure);
        }
    }

    @Override
    public boolean ping() {
        check();
        return true;
    }

    @Override
    public List<String> lrange(String key, long start, long end) {
        check();
        List<String> list = lists.getOrDefault(key, List.of());
        long from = start < 0 ? list.size() + start : start;
        long to = end < 0 ? list.size() + end : end;
        from = Math.max(from, 0);
        to = Math.min(to, list.size() - 1);
        if (from > to || list.isEmpty()) {
            return List.of();
        }
        return List.copyOf(list.subList((int) from, (int) to + 1));
    }

    @Override
    public long rpush(String key, String... values) {
        check();
        List<String> list = lists.computeIfAbsent(key, k -> new ArrayList<>());
        for (String value : values) {
            list.add(value);
        }
        return list.size();
    }

    @Override
    public void ltrim(String key, long start, long end) {
        check();
        List<String> list = lists.get(key);
        if (list == null) {
            return;
        }
        int from = (int) Math.max(start < 0 ? list.size() + start : start, 0);
        int to = (int) Math.min(end < 0 ? list.size() + end : end, list.size() - 1);
        List<String> trimmed = from > to ? List.of() : new ArrayList<>(list.subList(from, to + 1));
        lists.put(key, new ArrayList<>(trimmed));
    }

    @Override
    public long llen(String key) {
        check();
        return lists.getOrDefault(key, List.of()).size();
    }

    @Override
    public void expire(String key, long seconds) {
        check();
    }

    @Override
    public String get(String key) {
        check();
        return strings.get(key);
    }

    @Override
    public void setex(String key, long seconds, String value) {
        check();
        strings.put(key, value);
    }

    @Override
    public boolean setIfAbsent(String key, String value, long ttlMillis) {
        check();
        if (strings.containsKey(key)) {
            return false;
        }
        strings.put(key, value);
        lockedKeys.add(key);
        return true;
    }

    @Override
    public boolean setIfAbsentSeconds(String key, String value, long ttlSeconds) {
        check();
        if (strings.containsKey(key)) {
            return false;
        }
        strings.put(key, value);
        lockedKeys.add(key);
        return true;
    }

    @Override
    public Map<String, String> hgetAll(String key) {
        check();
        return new LinkedHashMap<>(hashes.getOrDefault(key, Map.of()));
    }

    @Override
    public void hset(String key, Map<String, String> values) {
        check();
        hashes.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).putAll(values);
    }

    @Override
    public void hdel(String key, String... fields) {
        check();
        Map<String, String> hash = hashes.get(key);
        if (hash != null) {
            for (String field : fields) {
                hash.remove(field);
            }
        }
    }

    @Override
    public void del(String key) {
        check();
        lists.remove(key);
        strings.remove(key);
        hashes.remove(key);
    }

    @Override
    public long rpushAndTrimAndExpire(String key, String[] values, long keepLast, long ttlSeconds) {
        check();
        List<String> list = lists.computeIfAbsent(key, k -> new ArrayList<>());
        for (String value : values) {
            list.add(value);
        }
        if (list.size() > keepLast) {
            int from = (int) (list.size() - keepLast);
            List<String> trimmed = new ArrayList<>(list.subList(from, list.size()));
            lists.put(key, trimmed);
        }
        return list.size();
    }
}
