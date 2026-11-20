package com.yusijia.houseclientservices.agent.memory.postgres;

import com.yusijia.houseclientservices.agent.memory.ChatDigestStore;
import com.yusijia.houseclientservices.db.PostgresDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 摘要落在独立 PostgreSQL 库（不与 MySQL 业务库混）。
 * <p>
 * 为什么不复用业务库：业务库是事务型的，摘要表是纯追加的低频写入，
 * 分开之后摘要库挂了不影响认购业务，业务库迁移也不用带着这张表走。
 * <p>
 * 连接池由 {@link PostgresDataSource} 持有，本类只负责 {@code chat_digest} 这一张表：
 * 表结构、SQL、可用性判断都在这里，池子的生命周期在外面。
 * 任何数据库异常都只是禁用摘要持久化——Redis 里还有一份摘要，对话照常进行。
 */
public class PostgresChatDigestStore implements ChatDigestStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresChatDigestStore.class);

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS chat_digest (
              id              BIGSERIAL PRIMARY KEY,
              conversation_id VARCHAR(128) NOT NULL,
              digest          TEXT         NOT NULL,
              created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
            )
            """;

    private static final String DDL_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_chat_digest_cid_id ON chat_digest (conversation_id, id DESC)
            """;

    private final PostgresDataSource pg;

    public PostgresChatDigestStore(PostgresDataSource pg) {
        this.pg = pg;
        pg.execute(DDL);
        pg.execute(DDL_INDEX);
        if (pg.isAvailable()) {
            log.info("对话摘要库已就绪: pool={}", pg.poolName());
        }
    }

    public boolean isAvailable() {
        return pg.isAvailable();
    }

    @Override
    public String latest(String conversationId) {
        if (!pg.isAvailable()) {
            return null;
        }
        try {
            List<String> rows = pg.jdbc().queryForList(
                    "SELECT digest FROM chat_digest WHERE conversation_id = ? ORDER BY id DESC LIMIT 1",
                    String.class, conversationId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException e) {
            log.warn("读取对话摘要失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void save(String conversationId, String digest) {
        if (!pg.isAvailable()) {
            return;
        }
        try {
            pg.jdbc().update("INSERT INTO chat_digest (conversation_id, digest) VALUES (?, ?)",
                    conversationId, digest);
        } catch (RuntimeException e) {
            log.warn("写入对话摘要失败: {}", e.getMessage());
        }
    }

    @Override
    public void delete(String conversationId) {
        if (!pg.isAvailable()) {
            return;
        }
        try {
            pg.jdbc().update("DELETE FROM chat_digest WHERE conversation_id = ?", conversationId);
        } catch (RuntimeException e) {
            log.warn("清理对话摘要失败: {}", e.getMessage());
        }
    }
}
