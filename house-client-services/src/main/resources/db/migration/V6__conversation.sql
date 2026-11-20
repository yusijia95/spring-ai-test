-- =============================================================================
-- V6：会话归属（P0-1 用户上下文骨架）
-- 一人多会话：一个登录用户可拥有多个会话，conversation_id 即前端传给 /api/agent/chat 的 id。
-- 归属校验：用户只能读写 owner_user_id 等于自己的会话，防止跨用户串话。
--
-- conversation_id 用 BIGINT 雪花 ID 由应用生成（与全库主键约定一致），
-- 但前端拿到的就是这一个值，作为记忆层（Redis/PG）的 conversationId 使用。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE conversation (
    id            BIGINT UNSIGNED NOT NULL COMMENT '会话ID（雪花，应用生成；同时作为记忆层 conversationId）',
    owner_user_id BIGINT UNSIGNED NOT NULL COMMENT '归属用户ID（user_account.id）',
    title         VARCHAR(100)    NULL     COMMENT '会话标题（首条消息摘要，可后补）',
    status        TINYINT         NOT NULL DEFAULT 1 COMMENT '状态：1进行中 0已归档',
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '建立时间',
    updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_conversation_owner (owner_user_id, status),
    KEY idx_conversation_updated (updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT ='会话（一人多会话，归属校验用）';
