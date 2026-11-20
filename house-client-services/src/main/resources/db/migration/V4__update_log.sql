-- =============================================================================
-- V4：更新日志表（change log）
-- 记录业务/配置变更，供追溯与审计。
--
-- 设计约定（与 V1 一致）：
-- 1. 主键为应用层雪花 ID（MyBatis-Plus IdType.ASSIGN_ID），BIGINT UNSIGNED，
--    JSON 序列化时转 String 避免前端 JS 精度丢失。
-- 2. 只追加不更新、不删除：日志是事实记录，UPDATE/DELETE 语句禁止。
-- 3. 无 deleted_flag：日志不做逻辑删除（删了就不存在了，违反审计语义）。
-- 4. 敏感字段（args/summary）写入前过 PiiMasker 脱敏，不落明文 PII。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS update_log (
    id            BIGINT UNSIGNED NOT NULL COMMENT '日志ID（雪花）',
    scope         VARCHAR(64)     NOT NULL COMMENT '变更范围：skill/prompt/tool/config/memory/rag/other',
    action        VARCHAR(32)     NOT NULL COMMENT '变更动作：create/update/delete/activate/deactivate/rollback',
    target_name   VARCHAR(200)    NOT NULL COMMENT '变更对象名（技能名/配置key/工具名等）',
    version       INT             NULL     COMMENT '版本号（可空；非版本化对象为 NULL）',
    summary       VARCHAR(500)    NOT NULL COMMENT '变更摘要（脱敏后）',
    detail        TEXT            NULL     COMMENT '变更详情/diff（脱敏后，可空）',
    operator      VARCHAR(64)     NULL     COMMENT '操作人（账号ID或 system）',
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录时间',
    PRIMARY KEY (id),
    KEY idx_ul_scope_target (scope, target_name),
    KEY idx_ul_action_time (action, created_at),
    KEY idx_ul_time (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT ='更新日志（只追加）';
