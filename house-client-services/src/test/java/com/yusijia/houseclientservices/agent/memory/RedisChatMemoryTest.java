package com.yusijia.houseclientservices.agent.memory;

import com.yusijia.houseclientservices.agent.memory.redis.ChatMessageCodec;
import com.yusijia.houseclientservices.agent.memory.redis.RedisChatMemory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RedisChatMemoryTest {

    private final FakeRedisOperations redis = new FakeRedisOperations();
    private final RecordingDigestStore digestStore = new RecordingDigestStore();
    private final FakeCompressor compressor = new FakeCompressor();

    private RedisChatMemory memory(int maxMessages, int maxChars, int keepRecent) {
        // A2：conversationId 是复合键 "u1:c1"（controller 层编码 userId），
        // keyPrefix 直接用 conversationId 构造 Redis key：chat:{u1:c1}:...
        return new RedisChatMemory(redis, new ChatMessageCodec(), digestStore, compressor,
                new ChatMemoryLimits(maxMessages, maxChars, keepRecent, 3600, 5000));
    }

    @Test
    void 超过条数上限只保留最近若干条() {
        RedisChatMemory memory = memory(4, 0, 2);
        for (int i = 0; i < 6; i++) {
            memory.add("u1:c1", new UserMessage("m" + i));
        }
        List<Message> window = memory.get("u1:c1");
        assertEquals(4, window.size());
        assertEquals("m2", window.get(0).getText());
        assertEquals("m5", window.get(3).getText());
    }

    @Test
    void 字符超预算时压缩最旧的一段并保留最近对话() {
        RedisChatMemory memory = memory(40, 60, 2);
        compressor.reply = (previous, messages) -> "客户在看 A 楼盘 101 室";
        memory.add("u1:c1", new UserMessage("一".repeat(40)));
        memory.add("u1:c1", new UserMessage("二".repeat(40)));
        memory.add("u1:c1", new UserMessage("三".repeat(40)));

        assertEquals("客户在看 A 楼盘 101 室", digestStore.latest("u1:c1"));
        assertEquals("客户在看 A 楼盘 101 室", redis.strings.get("chat:{u1:c1}:digest"));
        assertEquals(2, memory.get("u1:c1").size());
        assertEquals("二".repeat(40), memory.get("u1:c1").get(0).getText());
    }

    @Test
    void 摘要落库前脱敏_完整证件号不落() {
        RedisChatMemory memory = memory(40, 60, 2);
        // 摘要模型原样回显输入里的 PII（最坏情况），落库前必须被截成掩码
        compressor.reply = (previous, messages) -> "证件330102199001011234，电话13812345678";
        memory.add("u1:c1", new UserMessage("我的证件号是 330102199001011234，电话 13812345678。" + "补".repeat(20)));
        memory.add("u1:c1", new UserMessage("二".repeat(40)));
        memory.add("u1:c1", new UserMessage("三".repeat(40)));

        String stored = digestStore.latest("u1:c1");
        assertNotNull(stored);
        // 18 位身份证与 11 位手机号都不允许出现在 PG 摘要 / Redis 摘要缓存里
        assertEquals("证件**************1234，电话138****5678", stored);
        assertEquals(stored, redis.strings.get("chat:{u1:c1}:digest"));
        // 工作记忆窗口本身保留原文（模型下一轮要用完整证件号去 searchPerson）
        List<Message> window = memory.get("u1:c1");
        assertEquals(2, window.size());
        assertEquals("二".repeat(40), window.get(0).getText());
    }

    @Test
    void 压缩输入侧脱敏_完整证件号不进摘要模型() {
        RedisChatMemory memory = memory(40, 60, 2);
        // FakeCompressor 原样回显它收到的最后一条消息，用来断言"喂进去的是什么"
        compressor.reply = (previous, messages) -> messages.get(messages.size() - 1).getText();
        memory.add("u1:c1", new UserMessage("我的证件号是 330102199001011234。" + "补".repeat(20)));
        memory.add("u1:c1", new UserMessage("二".repeat(40)));
        memory.add("u1:c1", new UserMessage("三".repeat(40)));

        String seenByModel = digestStore.latest("u1:c1");
        assertNotNull(seenByModel);
        // 摘要模型收到的是脱敏文本，完整证件号没有进它的请求体
        assertEquals("我的证件号是 **************1234。" + "补".repeat(20), seenByModel);
    }

    @Test
    void 压缩失败退回截断且不抛异常() {
        RedisChatMemory memory = memory(40, 60, 2);
        compressor.reply = (previous, messages) -> null;
        memory.add("u1:c1", new UserMessage("一".repeat(40)));
        memory.add("u1:c1", new UserMessage("二".repeat(40)));
        memory.add("u1:c1", new UserMessage("三".repeat(40)));

        assertNull(digestStore.latest("u1:c1"));
        assertEquals(2, memory.get("u1:c1").size());
    }

    @Test
    void 锁被其他实例占用时跳过压缩() {
        RedisChatMemory memory = memory(40, 60, 2);
        compressor.reply = (previous, messages) -> "不该被写下";
        redis.strings.put("chat:{u1:c1}:lock:digest", "1");

        memory.add("u1:c1", new UserMessage("一".repeat(40)));
        memory.add("u1:c1", new UserMessage("二".repeat(40)));
        memory.add("u1:c1", new UserMessage("三".repeat(40)));

        assertNull(digestStore.latest("u1:c1"));
        assertEquals(3, memory.get("u1:c1").size());
    }

    @Test
    void Redis不可用时降级为进程内窗口() {
        RedisChatMemory memory = memory(10, 0, 5);
        redis.failure = new IllegalStateException("connection refused");

        memory.add("u1:c1", new UserMessage("第一句"));
        memory.add("u1:c1", new UserMessage("第二句"));

        List<Message> window = memory.get("u1:c1");
        assertEquals(2, window.size());
        assertEquals("第二句", window.get(1).getText());
    }

    @Test
    void 摘要缓存未命中时回源摘要库并回填() {
        RedisChatMemory memory = memory(10, 0, 5);
        digestStore.save("u1:c1", "客户预算 300 万");

        assertEquals("客户预算 300 万", memory.digest("u1:c1"));
        assertEquals("客户预算 300 万", redis.strings.get("chat:{u1:c1}:digest"));
    }

    @Test
    void clear同时清掉窗口摘要与摘要库() {
        RedisChatMemory memory = memory(10, 0, 5);
        memory.add("u1:c1", new UserMessage("我要看房"));
        digestStore.save("u1:c1", "摘要");

        memory.clear("u1:c1");

        assertEquals(0, memory.get("u1:c1").size());
        assertNull(digestStore.latest("u1:c1"));
    }

    @Test
    void 追加写入不会覆盖已有消息() {
        RedisChatMemory memory = memory(10, 0, 5);
        memory.add("u1:c1", new UserMessage("第一轮"));
        memory.add("u1:c1", List.of(new UserMessage("第二轮"), new org.springframework.ai.chat.messages.AssistantMessage("好的")));

        List<Message> window = memory.get("u1:c1");
        assertEquals(3, window.size());
        assertEquals("第一轮", window.get(0).getText());
        assertEquals("好的", window.get(2).getText());
        assertNotNull(window.get(1));
    }

    private static final class FakeCompressor implements ChatMemoryCompressor {
        BiFunction<String, List<Message>, String> reply = (previous, messages) -> null;

        @Override
        public String compress(String previousDigest, List<Message> messages) {
            return reply.apply(previousDigest, messages);
        }
    }

    private static final class RecordingDigestStore implements ChatDigestStore {
        private final List<String> digests = new ArrayList<>();

        @Override
        public String latest(String conversationId) {
            return digests.isEmpty() ? null : digests.get(digests.size() - 1);
        }

        @Override
        public void save(String conversationId, String digest) {
            digests.add(digest);
        }

        @Override
        public void delete(String conversationId) {
            digests.clear();
        }
    }
}
