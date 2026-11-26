package com.yusijia.houseclientservices.agent.memory.postgres;

import com.yusijia.houseclientservices.agent.memory.ChatMessageStore;
import com.yusijia.houseclientservices.db.PostgresDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 原始消息落在独立 PostgreSQL 库（与 {@code chat_digest} 同一个库、同一个池）。
 * <p>
 * {@code conversation_id} 存的是记忆作用域键（{@code userId:cid} 复合键），与 Redis key 完全一致：
 * 这样 Redis 与 PG 之间不需要任何键格式转换，回源时直接按同一个值查。
 * {@code user_id} 单独存一份字符串形式，便于按用户聚合（生成画像、归档），
 * 不用对 {@code conversation_id} 做前缀 LIKE。
 * <p>
 * 只增不改：对话是事实流，不存在"改一条历史消息"的场景；
 * 要修正只能追加新消息，这与审计表的思路一致。
 */
public class PostgresChatMessageStore implements ChatMessageStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresChatMessageStore.class);

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS chat_message (
              id              BIGSERIAL PRIMARY KEY,
              conversation_id VARCHAR(128) NOT NULL,
              user_id         VARCHAR(64)  NOT NULL DEFAULT '',
              role            VARCHAR(16)  NOT NULL,
              content         TEXT         NOT NULL,
              created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
            )
            """;

    private static final String DDL_CID_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_chat_message_cid ON chat_message (conversation_id, id)
            """;

    private static final String DDL_UID_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_chat_message_uid ON chat_message (user_id, id)
            """;

    private final PostgresDataSource pg;

    public PostgresChatMessageStore(PostgresDataSource pg) {
        this.pg = pg;
        pg.execute(DDL);
        pg.execute(DDL_CID_INDEX);
        pg.execute(DDL_UID_INDEX);
        if (pg.isAvailable()) {
            log.info("对话消息库已就绪: pool={}", pg.poolName());
        }
    }

    public boolean isAvailable() {
        return pg.isAvailable();
    }

    @Override
    public void append(String conversationId, String userId, List<Message> messages) {
        if (!pg.isAvailable() || !StringUtils.hasText(conversationId) || messages == null || messages.isEmpty()) {
            return;
        }
        List<Object[]> batch = new ArrayList<>(messages.size());
        for (Message message : messages) {
            String role = roleOf(message);
            String text = message == null ? null : message.getText();
            if (role == null || !StringUtils.hasText(text)) {
                continue;
            }
            batch.add(new Object[]{conversationId, userId == null ? "" : userId, role, text});
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            pg.jdbc().batchUpdate(
                    "INSERT INTO chat_message (conversation_id, user_id, role, content) VALUES (?, ?, ?, ?)",
                    batch);
        } catch (RuntimeException e) {
            log.warn("写入对话消息失败（本轮不持久化，聊天不受影响）: {}", e.getMessage());
        }
    }

    @Override
    public List<Message> recent(String conversationId, int limit) {
        List<Message> messages = new ArrayList<>();
        for (StoredMessage stored : recentWithTime(conversationId, limit)) {
            Message message = toMessage(stored.role(), stored.content());
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    @Override
    public List<StoredMessage> recentWithTime(String conversationId, int limit) {
        if (!pg.isAvailable() || !StringUtils.hasText(conversationId) || limit <= 0) {
            return List.of();
        }
        try {
            // 先按 id 倒序取最近 N 条，再反转成时间正序
            List<Map<String, Object>> rows = pg.jdbc().queryForList(
                    "SELECT role, content, created_at FROM chat_message "
                            + "WHERE conversation_id = ? ORDER BY id DESC LIMIT ?",
                    conversationId, limit);
            Collections.reverse(rows);
            List<StoredMessage> messages = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                messages.add(new StoredMessage(
                        String.valueOf(row.get("role")),
                        row.get("content") == null ? "" : String.valueOf(row.get("content")),
                        toOffsetDateTime(row.get("created_at"))));
            }
            return messages;
        } catch (RuntimeException e) {
            log.warn("读取对话消息失败: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public void delete(String conversationId) {
        if (!pg.isAvailable() || !StringUtils.hasText(conversationId)) {
            return;
        }
        try {
            pg.jdbc().update("DELETE FROM chat_message WHERE conversation_id = ?", conversationId);
        } catch (RuntimeException e) {
            log.warn("清理对话消息失败: {}", e.getMessage());
        }
    }

    /** 工具消息没有 tool_call_id，回放给模型会报错，与 {@code ChatMessageCodec} 保持一致：跳过 */
    private static String roleOf(Message message) {
        if (message == null) {
            return null;
        }
        MessageType type = message.getMessageType();
        return switch (type) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
            default -> null;
        };
    }

    private static Message toMessage(String role, String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        return switch (role == null ? "" : role) {
            case "user" -> new UserMessage(content);
            case "assistant" -> new AssistantMessage(content);
            case "system" -> new SystemMessage(content);
            default -> null;
        };
    }

    /** TIMESTAMPTZ 在 JDBC 里可能是 OffsetDateTime / Timestamp / Instant，统一转成 OffsetDateTime */
    private static OffsetDateTime toOffsetDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt;
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant().atOffset(ZoneOffset.UTC);
        }
        if (value instanceof Instant instant) {
            return instant.atOffset(ZoneOffset.UTC);
        }
        return null;
    }
}
