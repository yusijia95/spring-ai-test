package com.yusijia.houseclientservices.agent.memory.postgres;

import com.yusijia.houseclientservices.agent.memory.UserProfileStore;
import com.yusijia.houseclientservices.db.PostgresDataSource;
import com.yusijia.houseclientservices.security.PiiMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户画像落在独立 PostgreSQL 库（与 chat_digest / chat_message 同一个池）。
 * <p>
 * 表结构：user_id (PK 的一部分) + profile_key (PK 的一部分) → 每个用户每个字段只有一行。
 * upsert 用 ON CONFLICT DO UPDATE。
 * <p>
 * 任何数据库异常都只是禁用画像读写——对话照常进行，只是少一段长期背景。
 */
public class PostgresUserProfileStore implements UserProfileStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresUserProfileStore.class);

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS user_profile (
              user_id       VARCHAR(64)  NOT NULL,
              profile_key   VARCHAR(64)  NOT NULL,
              profile_value TEXT         NOT NULL,
              source        VARCHAR(128) NOT NULL DEFAULT '',
              updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
              PRIMARY KEY (user_id, profile_key)
            )
            """;

    private static final String DDL_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_user_profile_uid ON user_profile (user_id, updated_at DESC)
            """;

    private final PostgresDataSource pg;

    public PostgresUserProfileStore(PostgresDataSource pg) {
        this.pg = pg;
        pg.execute(DDL);
        pg.execute(DDL_INDEX);
        if (pg.isAvailable()) {
            log.info("用户画像库已就绪: pool={}", pg.poolName());
        }
    }

    @Override
    public Map<String, String> get(String userId) {
        if (!pg.isAvailable() || !StringUtils.hasText(userId)) {
            return Map.of();
        }
        try {
            List<Map<String, Object>> rows = pg.jdbc().queryForList(
                    "SELECT profile_key, profile_value FROM user_profile WHERE user_id = ? ORDER BY updated_at DESC",
                    userId);
            Map<String, String> result = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                String key = String.valueOf(row.get("profile_key"));
                String value = row.get("profile_value") == null ? "" : String.valueOf(row.get("profile_value"));
                result.putIfAbsent(key, value);
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("读取用户画像失败: userId={}, error={}", userId, e.getMessage());
            return Map.of();
        }
    }

    @Override
    public void upsert(String userId, String key, String value, String source) {
        if (!pg.isAvailable() || !StringUtils.hasText(userId) || !StringUtils.hasText(key)) {
            return;
        }
        String safeValue = PiiMasker.maskForStorage(value == null ? "" : value.trim());
        if (safeValue.length() > 500) {
            safeValue = safeValue.substring(0, 500);
        }
        String safeSource = PiiMasker.maskForStorage(source == null ? "" : source.trim());
        if (safeSource.length() > 128) {
            safeSource = safeSource.substring(0, 128);
        }
        try {
            pg.jdbc().update("""
                    INSERT INTO user_profile (user_id, profile_key, profile_value, source, updated_at)
                    VALUES (?, ?, ?, ?, now())
                    ON CONFLICT (user_id, profile_key)
                    DO UPDATE SET profile_value = EXCLUDED.profile_value,
                                  source = EXCLUDED.source,
                                  updated_at = now()
                    """, userId, key.trim(), safeValue, safeSource);
            log.info("用户画像已更新: userId={}, key={}, value={}", userId, key, safeValue);
        } catch (RuntimeException e) {
            log.warn("写入用户画像失败: userId={}, key={}, error={}", userId, key, e.getMessage());
        }
    }
}
