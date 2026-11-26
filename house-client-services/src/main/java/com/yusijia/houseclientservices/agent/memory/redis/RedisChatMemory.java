package com.yusijia.houseclientservices.agent.memory.redis;

import com.yusijia.houseclientservices.agent.memory.AgentChatMemory;
import com.yusijia.houseclientservices.agent.memory.ChatDigestStore;
import com.yusijia.houseclientservices.agent.memory.ChatMemoryCompressor;
import com.yusijia.houseclientservices.agent.memory.ChatMemoryLimits;
import com.yusijia.houseclientservices.agent.memory.ChatMessageStore;
import com.yusijia.houseclientservices.security.PiiMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作记忆：Redis LIST 追加写入，读时取最近一段。
 * <p>
 * 为什么不用 {@code MessageWindowChatMemory + JdbcChatMemoryRepository}：
 * 它的 {@code saveAll} 是"删掉整个会话再插入整窗口"，两个实例并发时后写覆盖先写，直接丢一轮对话；
 * 而且它按条数截断，不按上下文预算。这里改成 append-only：
 * <ul>
 *   <li>写入只 {@code RPUSH}，不依赖读结果 → <b>不需要 conversationId 锁</b>；</li>
 *   <li>{@code LTRIM} 保留最近 maxMessages 条，TTL 到期自动回收；</li>
 *   <li>字符数超预算时把最旧的一段压成摘要（写 PG + Redis），失败则退回截断；</li>
 *   <li>原始消息同步落 PG（{@link ChatMessageStore}），Redis 只是读缓存；</li>
 *   <li>Redis 不可用时自动降级：仍写 PG，读时从 PG 回源，聊天不会 500。</li>
 * </ul>
 * Key 统一带 {@code {cid}} 哈希标签：将来换 Redis Cluster 时同一会话的 key 落在同一 slot。
 * A2 起 key 带 userId 命名空间：{@code chat:{userId:cid}:...}，按用户清理和审计更方便。
 */
public class RedisChatMemory implements AgentChatMemory {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemory.class);

    /** 熔断窗口：Redis 出错后这段时间内直接走本地窗口，避免每条消息都等一次连接超时 */
    private static final long DEGRADE_MILLIS = 30_000L;

    /** 窗口与预算 */
    private final RedisOperations redis;
    private final ChatMessageCodec codec;
    private final ChatDigestStore digestStore;
    private final ChatMessageStore messageStore;
    private final ChatMemoryCompressor compressor;
    private final ChatMemoryLimits limits;

    /** Redis 不可用时的兜底窗口（单实例语义，不跨实例共享；PG 才是权威副本） */
    private final Map<String, Deque<Message>> localWindow = new ConcurrentHashMap<>();

    private volatile long degradedUntil;

    public RedisChatMemory(RedisOperations redis, ChatMessageCodec codec,
                           ChatDigestStore digestStore, ChatMessageStore messageStore,
                           ChatMemoryCompressor compressor, ChatMemoryLimits limits) {
        this.redis = redis;
        this.codec = codec;
        this.digestStore = digestStore;
        this.messageStore = messageStore;
        this.compressor = compressor;
        this.limits = limits;
    }

    @Override
    public void add(String conversationId, Message message) {
        add(conversationId, List.of(message));
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (!StringUtils.hasText(conversationId) || messages == null || messages.isEmpty()) {
            return;
        }
        List<String> payload = new ArrayList<>(messages.size());
        for (Message message : messages) {
            String encoded = codec.encode(message);
            if (encoded != null) {
                payload.add(encoded);
            }
        }
        if (payload.isEmpty()) {
            return;
        }
        if (isDegraded()) {
            appendLocal(conversationId, messages);
            // Redis 已熔断：PG 是唯一落盘路径，否则这段对话彻底丢失
            persistMessages(conversationId, messages);
            return;
        }
        String key = messageKey(conversationId);
        try {
            redis.rpush(key, payload.toArray(new String[0]));
            redis.ltrim(key, -limits.maxMessages(), -1);
            redis.expire(key, limits.ttlSeconds());
            persistMessages(conversationId, messages);
            compactIfNeeded(conversationId, key);
        } catch (RuntimeException e) {
            degrade(e);
            appendLocal(conversationId, messages);
            persistMessages(conversationId, messages);
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return List.of();
        }
        if (isDegraded()) {
            return loadFromStoreIfLocalEmpty(conversationId);
        }
        try {
            List<Message> window = decode(redis.lrange(messageKey(conversationId), 0, -1));
            if (!window.isEmpty()) {
                return window;
            }
            // Redis 窗口空了（TTL 到期 / 换实例 / 首次冷启动）→ 从 PG 重建
            List<Message> fromStore = messageStore.recent(conversationId, limits.maxMessages());
            if (!fromStore.isEmpty()) {
                backfill(conversationId, fromStore);
            }
            return fromStore;
        } catch (RuntimeException e) {
            degrade(e);
            return loadFromStoreIfLocalEmpty(conversationId);
        }
    }

    @Override
    public void clear(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return;
        }
        localWindow.remove(conversationId);
        try {
            redis.del(messageKey(conversationId));
            redis.del(digestKey(conversationId));
            redis.del(lockKey(conversationId));
        } catch (RuntimeException e) {
            degrade(e);
        }
        // 摘要和原始消息都要清：否则 clear 之后 get()/digest() 会从 PG 把它们读回来（记忆"复活"）
        digestStore.delete(conversationId);
        messageStore.delete(conversationId);
    }

    @Override
    public String digest(String conversationId) {
        if (!StringUtils.hasText(conversationId) || isDegraded()) {
            return null;
        }
        try {
            String cached = redis.get(digestKey(conversationId));
            if (StringUtils.hasText(cached)) {
                return cached;
            }
        } catch (RuntimeException e) {
            degrade(e);
            return null;
        }
        // 缓存未命中（TTL 到期 / 换了实例）回源 PG，顺带回填
        String fromStore = digestStore.latest(conversationId);
        if (StringUtils.hasText(fromStore)) {
            try {
                redis.setex(digestKey(conversationId), limits.ttlSeconds(), fromStore);
            } catch (RuntimeException e) {
                degrade(e);
            }
        }
        return fromStore;
    }

    /**
     * 硬上限（条数）先截，再由字符预算决定要不要压缩。
     * <p>
     * 只压缩、不因为条数压缩：条数超了直接丢最旧的整条，代价是一次性丢掉几轮闲聊；
     * 字符超了才值得花一次模型调用换摘要。
     */
    private void compactIfNeeded(String conversationId, String key) {
        List<String> raw = redis.lrange(key, 0, -1);
        if (raw.isEmpty()) {
            return;
        }
        int chars = 0;
        for (String item : raw) {
            chars += item.length();
        }
        if (limits.maxChars() <= 0 || chars <= limits.maxChars() || compressor == null) {
            return;
        }
        int compressCount = raw.size() - limits.keepRecent();
        if (compressCount <= 0) {
            // 最近 keepRecent 条本身就超预算，只能不再压缩，避免把当前上下文清空
            log.debug("记忆超出字符预算但无需压缩: conversationId={}, chars={}", conversationId, chars);
            return;
        }
        if (compressor == null) {
            redis.ltrim(key, -limits.keepRecent(), -1);
            return;
        }
        // 压缩是唯一需要互斥的动作：两个实例同时压缩会生成两份摘要并各自截断窗口。
        // 抢不到锁就跳过，下一轮再压；绝不阻塞本轮回答。
        String lockKey = lockKey(conversationId);
        if (!redis.setIfAbsent(lockKey, "1", limits.lockTtlMillis())) {
            return;
        }
        try {
            // A3：喂给摘要模型的旧消息先脱敏——PII 不进摘要模型的请求体，
            // 摘要模型是便宜的外部模型，它的调用日志/训练采样可能留存 prompt。
            // 工作记忆窗口本身保留原文：模型下一轮要用用户报的完整证件号去 searchPerson，
            // 窗口存掩码会直接破坏认购流程。
            String previous = PiiMasker.maskForStorage(digest(conversationId));
            String newDigest = PiiMasker.maskForStorage(
                    compressor.compress(previous, maskMessages(decode(raw.subList(0, compressCount)))));
            if (!StringUtils.hasText(newDigest)) {
                // 压缩失败：退回截断到 keepRecent，至少保证下一轮不超上下文
                redis.ltrim(key, -limits.keepRecent(), -1);
                log.warn("记忆压缩失败，已退回截断: conversationId={}", conversationId);
                return;
            }
            digestStore.save(conversationId, newDigest);
            redis.setex(digestKey(conversationId), limits.ttlSeconds(), newDigest);
            redis.ltrim(key, compressCount, -1);
            log.info("记忆已压缩: conversationId={}, 压缩 {} 条, 保留 {} 条, 摘要 {} 字",
                    conversationId, compressCount, limits.keepRecent(), newDigest.length());
        } finally {
            // 锁的 TTL 才是真正的兜底；这里主动释放只是让下一轮能立刻再压。
            // 极端情况下（本轮耗时超过 lockTtl）可能删掉别的实例刚拿到的锁，代价是多一次重复压缩，不会丢消息。
            try {
                redis.del(lockKey);
            } catch (RuntimeException e) {
                degrade(e);
            }
        }
    }

    private List<Message> decode(List<String> raw) {
        List<Message> messages = new ArrayList<>(raw.size());
        for (String item : raw) {
            Message message = codec.decode(item);
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    /** A3：摘要输入侧脱敏（只用于喂给摘要模型，工作记忆窗口本身不动） */
    private List<Message> maskMessages(List<Message> messages) {
        List<Message> masked = new ArrayList<>(messages.size());
        for (Message message : messages) {
            String text = message.getText();
            String maskedText = PiiMasker.maskForStorage(text);
            if (maskedText == null) {
                masked.add(message);
                continue;
            }
            masked.add(switch (message.getMessageType()) {
                case USER -> new UserMessage(maskedText);
                case ASSISTANT -> new AssistantMessage(maskedText);
                case SYSTEM -> new SystemMessage(maskedText);
                default -> message;
            });
        }
        return masked;
    }

    /** 原始消息落 PG（失败由 store 内部吞掉并 warn，不影响本轮回答） */
    private void persistMessages(String conversationId, List<Message> messages) {
        messageStore.append(conversationId, scopeUser(conversationId), messages);
    }

    /** 从作用域键 {@code userId:cid} 取出 userId；没有前缀时返回空串 */
    private static String scopeUser(String scopedConversationId) {
        int idx = scopedConversationId.indexOf(':');
        return idx > 0 ? scopedConversationId.substring(0, idx) : "";
    }

    /**
     * PG 回源后回填 Redis 缓存。
     * 回填前再看一眼 llen：并发请求可能已经填过了，重复 RPUSH 会让消息翻倍。
     */
    private void backfill(String conversationId, List<Message> messages) {
        try {
            String key = messageKey(conversationId);
            if (redis.llen(key) > 0) {
                return;
            }
            List<String> payload = new ArrayList<>(messages.size());
            for (Message message : messages) {
                String encoded = codec.encode(message);
                if (encoded != null) {
                    payload.add(encoded);
                }
            }
            if (payload.isEmpty()) {
                return;
            }
            redis.rpush(key, payload.toArray(new String[0]));
            redis.expire(key, limits.ttlSeconds());
            log.info("工作记忆已从 PG 回填: conversationId={}, 条数={}", conversationId, payload.size());
        } catch (RuntimeException e) {
            degrade(e);
        }
    }

    /**
     * 降级态读取：本地窗口优先（本轮刚写的），没有再回源 PG（跨实例/重启后的历史）。
     * 顺序不能反——本地窗口里有 PG 还没写入的消息（降级瞬间的那一轮）。
     */
    private List<Message> loadFromStoreIfLocalEmpty(String conversationId) {
        List<Message> local = localSnapshot(conversationId);
        if (!local.isEmpty()) {
            return local;
        }
        return messageStore.recent(conversationId, limits.maxMessages());
    }

    private void appendLocal(String conversationId, List<Message> messages) {
        Deque<Message> window = localWindow.computeIfAbsent(conversationId, key -> new ArrayDeque<>());
        synchronized (window) {
            for (Message message : messages) {
                window.addLast(message);
            }
            while (window.size() > limits.maxMessages()) {
                window.pollFirst();
            }
        }
    }

    private List<Message> localSnapshot(String conversationId) {
        Deque<Message> window = localWindow.get(conversationId);
        if (window == null) {
            return List.of();
        }
        synchronized (window) {
            return List.copyOf(window);
        }
    }

    private boolean isDegraded() {
        return System.currentTimeMillis() < degradedUntil;
    }

    private void degrade(RuntimeException e) {
        degradedUntil = System.currentTimeMillis() + DEGRADE_MILLIS;
        log.warn("Redis 记忆不可用，{} 秒内降级为进程内窗口: {}",
                DEGRADE_MILLIS / 1000, Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
    }

    /**
     * 构建 Redis key 前缀。
     * <p>
     * conversationId 此时是 controller 层传入的复合键 {@code userId:cid}（A2），
     * 直接作为哈希标签：{@code chat:{userId:cid}:msg}，同一会话的所有 key
     * （msg/digest/lock）落在同一 Cluster slot。
     * 测试等未带 userId 的场景传纯 cid，格式退化为 {@code chat:{cid}:msg}。
     * <p>
     * 不用实例字段存 userId：singleton Bean 被所有请求共享，任何实例级状态
     * （volatile 字段 / ConcurrentHashMap）要么并发覆盖、要么无 TTL 导致 OOM。
     * 把 userId 编码进 conversationId 本身，零额外状态，天然并发安全。
     */
    private String keyPrefix(String conversationId) {
        return "chat:{" + conversationId + "}";
    }

    private String messageKey(String conversationId) {
        return keyPrefix(conversationId) + ":msg";
    }

    private String digestKey(String conversationId) {
        return keyPrefix(conversationId) + ":digest";
    }

    private String lockKey(String conversationId) {
        return keyPrefix(conversationId) + ":lock:digest";
    }
}
