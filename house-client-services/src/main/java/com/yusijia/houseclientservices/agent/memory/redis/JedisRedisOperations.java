package com.yusijia.houseclientservices.agent.memory.redis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.List;
import java.util.Map;

/**
 * {@link RedisOperations} 的 Jedis 实现。
 * <p>
 * 每个命令取一次连接（Jedis 的用法），连接池复用 TCP；
 * 记忆层的命令都是单 key 小对象操作，池子不需要很大。
 */
public class JedisRedisOperations implements RedisOperations {

    private final JedisPool pool;

    public JedisRedisOperations(JedisPool pool) {
        this.pool = pool;
    }

    @Override
    public boolean ping() {
        try (Jedis jedis = pool.getResource()) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        }
    }

    @Override
    public List<String> lrange(String key, long start, long end) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.lrange(key, start, end);
        }
    }

    @Override
    public long rpush(String key, String... values) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.rpush(key, values);
        }
    }

    @Override
    public void ltrim(String key, long start, long end) {
        try (Jedis jedis = pool.getResource()) {
            jedis.ltrim(key, start, end);
        }
    }

    @Override
    public long llen(String key) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.llen(key);
        }
    }

    @Override
    public void expire(String key, long seconds) {
        try (Jedis jedis = pool.getResource()) {
            jedis.expire(key, seconds);
        }
    }

    @Override
    public String get(String key) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(key);
        }
    }

    @Override
    public void setex(String key, long seconds, String value) {
        try (Jedis jedis = pool.getResource()) {
            jedis.setex(key, seconds, value);
        }
    }

    @Override
    public boolean setIfAbsent(String key, String value, long ttlMillis) {
        try (Jedis jedis = pool.getResource()) {
            return "OK".equals(jedis.set(key, value, SetParams.setParams().nx().px(ttlMillis)));
        }
    }

    @Override
    public boolean setIfAbsentSeconds(String key, String value, long ttlSeconds) {
        try (Jedis jedis = pool.getResource()) {
            return "OK".equals(jedis.set(key, value, SetParams.setParams().nx().ex(ttlSeconds)));
        }
    }

    @Override
    public Map<String, String> hgetAll(String key) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.hgetAll(key);
        }
    }

    @Override
    public void hset(String key, Map<String, String> values) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(key, values);
        }
    }

    @Override
    public void hdel(String key, String... fields) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hdel(key, fields);
        }
    }

    @Override
    public void del(String key) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(key);
        }
    }

    @Override
    public long rpushAndTrimAndExpire(String key, String[] values, long keepLast, long ttlSeconds) {
        try (Jedis jedis = pool.getResource()) {
            // Jedis 5.x: multi() 返回 Transaction，命令通过 Transaction 入队，exec() 原子执行
            var txn = jedis.multi();
            txn.rpush(key, values);
            txn.ltrim(key, -keepLast, -1);
            txn.expire(key, ttlSeconds);
            var results = txn.exec();
            // results[0] 是 RPUSH 返回的 list 长度
            if (results != null && !results.isEmpty()) {
                Object first = results.get(0);
                if (first instanceof Long l) {
                    return l;
                }
            }
            return -1;
        }
    }
}
