package com.yusijia.houseclientservices.agent.memory.postgres;

import com.yusijia.houseclientservices.agent.memory.FeedbackStore;
import com.yusijia.houseclientservices.db.PostgresDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 反馈记录的 PG 实现。
 * <p>
 * 表结构由构造时 DDL 自建（和 chat_digest 同一模式）。
 * 写入失败只记 warn，不抛异常——反馈是旁路能力，不能影响对话。
 */
public class PostgresFeedbackStore implements FeedbackStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresFeedbackStore.class);

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS agent_feedback (
              id              BIGSERIAL PRIMARY KEY,
              conversation_id VARCHAR(128) NOT NULL,
              user_id         VARCHAR(32)   NOT NULL,
              turn            INT           NOT NULL,
              rating          SMALLINT      NOT NULL,
              reason          TEXT,
              summary         TEXT,
              created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
            )
            """;

    private static final String DDL_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_feedback_cid ON agent_feedback (conversation_id, turn)
            """;

    private static final String DDL_INDEX_BAD = """
            CREATE INDEX IF NOT EXISTS idx_feedback_rating ON agent_feedback (rating, created_at DESC)
            """;

    private static final String SELECT_COLS =
            "SELECT id, conversation_id, user_id, turn, rating, reason, summary, created_at FROM agent_feedback";

    private final PostgresDataSource pg;

    public PostgresFeedbackStore(PostgresDataSource pg) {
        this.pg = pg;
        if (pg.isAvailable()) {
            pg.execute(DDL);
            pg.execute(DDL_INDEX);
            pg.execute(DDL_INDEX_BAD);
            log.info("反馈库已就绪: pool={}", pg.poolName());
        }
    }

    @Override
    public long save(String conversationId, String userId, int turn, int rating, String reason, String summary) {
        if (!pg.isAvailable()) {
            return -1;
        }
        try {
            List<Long> ids = pg.jdbc().queryForList(
                    "INSERT INTO agent_feedback (conversation_id, user_id, turn, rating, reason, summary) "
                            + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                    Long.class, conversationId, userId, turn, rating, reason, summary);
            return ids.isEmpty() ? -1 : ids.get(0);
        } catch (RuntimeException e) {
            log.warn("写入反馈失败: {}", e.getMessage());
            return -1;
        }
    }

    @Override
    public List<FeedbackRecord> byConversation(String conversationId) {
        if (!pg.isAvailable()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = pg.jdbc().queryForList(
                    SELECT_COLS + " WHERE conversation_id = ? ORDER BY turn, id",
                    conversationId);
            return mapRows(rows);
        } catch (RuntimeException e) {
            log.warn("查询反馈失败: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<FeedbackRecord> badCases(int limit) {
        if (!pg.isAvailable()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = pg.jdbc().queryForList(
                    SELECT_COLS + " WHERE rating = -1 ORDER BY created_at DESC LIMIT ?",
                    limit);
            return mapRows(rows);
        } catch (RuntimeException e) {
            log.warn("查询点踩记录失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static List<FeedbackRecord> mapRows(List<Map<String, Object>> rows) {
        List<FeedbackRecord> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Instant createdAt;
            Object raw = row.get("created_at");
            if (raw instanceof Timestamp ts) {
                createdAt = ts.toInstant();
            } else if (raw instanceof Instant inst) {
                createdAt = inst;
            } else {
                createdAt = Instant.EPOCH;
            }
            result.add(new FeedbackRecord(
                    ((Number) row.get("id")).longValue(),
                    String.valueOf(row.get("conversation_id")),
                    String.valueOf(row.get("user_id")),
                    ((Number) row.get("turn")).intValue(),
                    ((Number) row.get("rating")).intValue(),
                    row.get("reason") == null ? null : String.valueOf(row.get("reason")),
                    row.get("summary") == null ? null : String.valueOf(row.get("summary")),
                    createdAt));
        }
        return result;
    }
}
