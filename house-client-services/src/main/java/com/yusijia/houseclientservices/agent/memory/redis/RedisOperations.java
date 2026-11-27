package com.yusijia.houseclientservices.agent.memory.redis;

import java.util.List;
import java.util.Map;

/**
 * 记忆层用到的最小 Redis 命令集。
 * <p>
 * 只抽这一层、不直接依赖 Jedis：一是测试可以用内存实现（不需要真的起 Redis），
 * 二是以后换 Lettuce / 换集群客户端只改一个实现类。
 * <p>
 * 所有方法在 Redis 不可用时抛 {@link RuntimeException}，由调用方决定降级，
 * 不在这里吞异常——否则"写入成功"和"写入失败"就无法区分。
 */
public interface RedisOperations {

    /** 探活，用于启动日志和熔断后的恢复探测 */
    boolean ping();

    List<String> lrange(String key, long start, long end);

    long rpush(String key, String... values);

    void ltrim(String key, long start, long end);

    long llen(String key);

    void expire(String key, long seconds);

    String get(String key);

    void setex(String key, long seconds, String value);

    /**
     * SET key value NX PX ttl
     *
     * @return true 表示抢到锁（key 原本不存在）
     */
    boolean setIfAbsent(String key, String value, long ttlMillis);

    /**
     * SET key value NX EX ttlSeconds（秒级 TTL，幂等键 / 分布式锁用）。
     *
     * @return true 表示抢到（key 原本不存在）
     */
    boolean setIfAbsentSeconds(String key, String value, long ttlSeconds);

    Map<String, String> hgetAll(String key);

    void hset(String key, Map<String, String> values);

    void hdel(String key, String... fields);

    void del(String key);

    /**
     * MULTI/EXEC 原子写入：RPUSH + LTRIM + EXPIRE 一次事务完成。
     * <p>
     * 解决高并发下 rpush 成功但 ltrim/expire 失败时窗口超上限或 key 永不过期的问题。
     * 单连接、单 RTT，比三次独立调用快且原子。
     *
     * @return RPUSH 执行后 list 的长度
     */
    long rpushAndTrimAndExpire(String key, String[] values, long keepLast, long ttlSeconds);
}
