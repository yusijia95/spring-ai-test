package com.yusijia.houseclientservices.agent.memory.llm;

import com.yusijia.houseclientservices.agent.memory.ProfileExtractor;
import com.yusijia.houseclientservices.agent.memory.UserProfileStore;
import com.yusijia.houseclientservices.agent.memory.redis.RedisOperations;
import com.yusijia.houseclientservices.security.PiiMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用便宜模型从对话中提取长期偏好，写入 L3 用户画像。
 * <p>
 * 复用压缩器的 ChatClient（同一个便宜模型），不额外配模型。
 * 提示词要求模型输出 JSON 数组，解析失败时静默跳过。
 * <p>
 * <b>生产防护</b>（压缩成功后被异步线程池调用，不能卡主流程、不能重复计费、不能并发覆盖）：
 * <ul>
 *   <li><b>幂等去重</b>：同一批旧消息（按 userId + 消息哈希）只提取一次。
 *       标记落 Redis（跨实例共享，TTL 24h），Redis 不可用时退化为进程内缓存。
 *       防止"压缩报错回滚 → 下次重提"或"两个实例同时压"导致大模型重复计费。</li>
 *   <li><b>按 userId 串行</b>：提取前抢一个 userId 粒度的分布式锁（TTL 30s），
 *       两个异步任务同时更新同一用户画像时后到者跳过，避免"先算后写"的覆盖冲突。
 *       拿不到锁直接跳过——画像只是晚更新一轮，不丢数据。</li>
 * </ul>
 */
public class LlmProfileExtractor implements ProfileExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmProfileExtractor.class);

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "budget", "districtPref", "roomPref", "purpose", "other");

    /** 幂等标记 TTL（秒）：同一批消息 24h 内不重复提取 */
    private static final long IDEMPOTENT_TTL_SECONDS = 86400L;
    /** 按 userId 锁 TTL（秒）：单次 LLM 提取远短于此，30s 足够兜底 */
    private static final long USER_LOCK_TTL_SECONDS = 30L;

    private static final String SYSTEM = """
            你是客服对话的画像提取器。从给定的对话中识别客户<b>明确说过</b>的长期偏好。

            只提取以下字段（客户必须明确表达，禁止推测）：
            - budget：客户预算（如"300万左右"）
            - districtPref：意向区域（如"江宁区，靠近地铁"）
            - roomPref：户型偏好（如"三室两厅"）
            - purpose：购房目的（如"学区""自住""投资"）
            - other：其他长期偏好

            严格规则：
            - 只提取客户（"客户："行）明确说过的内容，客服说的不算
            - 如果客户没有明确提到某个字段，不要输出该字段
            - 值尽量简短（20 字以内），保留客户原话的关键信息
            - 不要提取当前在谈的具体楼盘、房号、认购单号（那是会话状态，不是长期偏好）

            输出格式（纯 JSON 数组，不要 Markdown 代码块）：
            [{"key":"budget","value":"300万左右"},{"key":"districtPref","value":"江宁区，靠近地铁"}]
            如果没有可提取的偏好，输出：[]
            """;

    private final ChatClient chatClient;
    private final UserProfileStore profileStore;
    private final String model;
    /** 幂等标记 + userId 锁都走 Agent Redis（与记忆层同一个池），可为 null（测试 / 退化） */
    private final RedisOperations redis;
    /** Redis 不可用时的进程内幂等缓存（单实例语义，重启即清空，可接受） */
    private final Set<String> localIdempotent = ConcurrentHashMap.newKeySet();

    public LlmProfileExtractor(ChatClient chatClient, UserProfileStore profileStore, String model,
                               RedisOperations redis) {
        this.chatClient = chatClient;
        this.profileStore = profileStore;
        this.model = model;
        this.redis = redis;
    }

    @Override
    public Map<String, String> extract(String userId, List<Message> messages) {
        if (userId == null || userId.isBlank() || messages == null || messages.isEmpty()) {
            return Map.of();
        }
        // 1. 幂等去重：同一批消息（userId + 内容哈希）只提取一次，防大模型重复计费
        String batchHash = batchHash(userId, messages);
        if (isAlreadyExtracted(batchHash)) {
            log.debug("画像提取跳过（该批消息已提取过）: userId={}, batch={}", userId, shortHash(batchHash));
            return Map.of();
        }
        // 2. 按 userId 抢分布式锁：同一用户的画像提取串行，防并发覆盖
        String lockKey = profileLockKey(userId);
        if (!acquireUserLock(lockKey)) {
            log.info("画像提取跳过（该用户已有提取任务在跑）: userId={}", userId);
            return Map.of();
        }
        try {
            String answer = chatClient.prompt()
                    .system(SYSTEM)
                    .user(buildInput(messages))
                    .call()
                    .content();
            if (!StringUtils.hasText(answer)) {
                markExtracted(batchHash);
                return Map.of();
            }
            Map<String, String> extracted = parseJson(answer);
            // 3. 写画像 + 落幂等标记。即使 extracted 为空也打标记：
            //    "这批消息确实没有可提取的偏好"同样是已处理，不该再调一次模型。
            for (Map.Entry<String, String> entry : extracted.entrySet()) {
                profileStore.upsert(userId, entry.getKey(), entry.getValue(), "对话自动提取");
            }
            markExtracted(batchHash);
            if (!extracted.isEmpty()) {
                log.info("画像自动提取: userId={}, fields={}", userId, extracted.keySet());
            }
            return extracted;
        } catch (RuntimeException e) {
            // 提取失败不打幂等标记：下次再触发时还能重试（消息还在窗口里）
            log.warn("画像提取失败（model={}），跳过: {}", model, e.getMessage());
            return Map.of();
        } finally {
            releaseUserLock(lockKey);
        }
    }

    // ==================== 幂等去重 ====================

    private String batchHash(String userId, List<Message> messages) {
        // 用脱敏后的消息内容做哈希：同一批消息无论原文 PII 如何，哈希一致
        StringBuilder sb = new StringBuilder(userId);
        for (Message message : messages) {
            String text = message.getText();
            String masked = text == null ? "" : PiiMasker.maskForStorage(text);
            sb.append('\u0001').append(message.getMessageType()).append(masked);
        }
        return sha256(sb.toString());
    }

    private boolean isAlreadyExtracted(String batchHash) {
        String key = "extract:profile:done:" + batchHash;
        if (redis != null) {
            try {
                return redis.get(key) != null;
            } catch (RuntimeException e) {
                // Redis 不可用：退化为进程内缓存（单实例语义，重启即清空，可接受）
                log.debug("幂等查询走进程内缓存（Redis 不可用）: {}", e.getMessage());
            }
        }
        return localIdempotent.contains(batchHash);
    }

    private void markExtracted(String batchHash) {
        localIdempotent.add(batchHash);
        String key = "extract:profile:done:" + batchHash;
        if (redis != null) {
            try {
                redis.setex(key, IDEMPOTENT_TTL_SECONDS, "1");
            } catch (RuntimeException e) {
                log.debug("幂等标记写 Redis 失败（已记进程内缓存）: {}", e.getMessage());
            }
        }
    }

    // ==================== 按 userId 分布式锁 ====================

    private boolean acquireUserLock(String lockKey) {
        if (redis == null) {
            // 无 Redis：单实例语义，直接放行（进程内不会有并发异步任务）
            return true;
        }
        try {
            return redis.setIfAbsentSeconds(lockKey, "1", USER_LOCK_TTL_SECONDS);
        } catch (RuntimeException e) {
            // Redis 抖动时不阻塞画像提取：放行，最坏是两次并发写同一 key（ON CONFLICT 幂等）
            log.debug("userId 锁获取失败，放行提取: {}", e.getMessage());
            return true;
        }
    }

    private void releaseUserLock(String lockKey) {
        if (redis == null) {
            return;
        }
        try {
            redis.del(lockKey);
        } catch (RuntimeException e) {
            // 锁有 TTL 兜底，主动释放失败只影响"下次能多快再提"，不丢数据
            log.debug("userId 锁释放失败（TTL 兜底）: {}", e.getMessage());
        }
    }

    private static String profileLockKey(String userId) {
        return "lock:extract:profile:{" + userId + "}";
    }

    private static String shortHash(String hash) {
        return hash.length() > 12 ? hash.substring(0, 12) : hash;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            // SHA-256 一定可用；这里兜底返回稳定值，保证幂等键不抖动
            return Integer.toHexString(input.hashCode());
        }
    }

    private String buildInput(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            String text = message.getText();
            if (!StringUtils.hasText(text)) {
                continue;
            }
            sb.append(message.getMessageType() == MessageType.USER ? "客户：" : "客服：")
                    .append(text.trim())
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * 解析模型输出的 JSON 数组。
     * 容忍：前后有多余文字、Markdown 代码块包裹、单引号等。
     */
    static Map<String, String> parseJson(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        // 提取 JSON 数组部分
        String json = raw.trim();
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return result;
        }
        json = json.substring(start, end + 1);
        // 手动解析简单的 [{"key":"...","value":"..."}] 格式
        // 不用 Jackson：模型输出可能不严格，手动解析更容错
        int i = 0;
        while (i < json.length()) {
            int objStart = json.indexOf('{', i);
            if (objStart < 0 || objStart > end) {
                break;
            }
            int objEnd = json.indexOf('}', objStart);
            if (objEnd < 0) {
                break;
            }
            String obj = json.substring(objStart + 1, objEnd);
            String key = extractField(obj, "key");
            String value = extractField(obj, "value");
            if (key != null && ALLOWED_KEYS.contains(key) && value != null && !value.isBlank()) {
                result.put(key, value.trim());
            }
            i = objEnd + 1;
        }
        return result;
    }

    private static String extractField(String obj, String fieldName) {
        // 匹配 "fieldName":"value" 或 'fieldName':'value'
        String pattern = "\"" + fieldName + "\"";
        int idx = obj.indexOf(pattern);
        if (idx < 0) {
            pattern = "'" + fieldName + "'";
            idx = obj.indexOf(pattern);
        }
        if (idx < 0) {
            return null;
        }
        int colonIdx = obj.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) {
            return null;
        }
        int valStart = colonIdx + 1;
        // 跳过空格
        while (valStart < obj.length() && obj.charAt(valStart) == ' ') {
            valStart++;
        }
        if (valStart >= obj.length()) {
            return null;
        }
        char quote = obj.charAt(valStart);
        if (quote != '"' && quote != '\'') {
            return null;
        }
        int valEnd = obj.indexOf(quote, valStart + 1);
        if (valEnd < 0) {
            return null;
        }
        return obj.substring(valStart + 1, valEnd);
    }
}
