package com.yusijia.houseclientservices.agent.memory;

import com.yusijia.houseclientservices.agent.memory.redis.RedisSessionStateStore;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStateStoreTest {

    private final FakeRedisOperations redis = new FakeRedisOperations();
    private final SessionStateStore repository = new RedisSessionStateStore(redis, 3600);

    @Test
    void 写入后能读回全部字段() {
        repository.put("u1", "c1", "currentHouse", "联发云启 101 室 4001");
        repository.put("u1", "c1", "budget", "300 万");

        Map<String, String> state = repository.get("u1", "c1");
        assertEquals("联发云启 101 室 4001", state.get("currentHouse"));
        assertEquals("300 万", state.get("budget"));
    }

    @Test
    void 未登记的字段直接拒绝() {
        assertThrows(IllegalArgumentException.class, () -> repository.put("u1", "c1", "闲聊", "天气不错"));
    }

    @Test
    void Redis不可用时退化为进程内副本() {
        redis.failure = new IllegalStateException("connection refused");
        repository.put("u1", "c1", "budget", "300 万");

        assertEquals("300 万", repository.get("u1", "c1").get("budget"));
        assertTrue(redis.hashes.isEmpty());
    }
}
