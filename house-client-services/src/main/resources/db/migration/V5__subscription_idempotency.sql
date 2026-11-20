-- =============================================================================
-- V5：认购业务幂等键
-- 防止模型重复调用 createSubscription 时创建重复认购。
--
-- client_token 由调用方（SubscriptionTools）根据入参（houseIds+personIds+orgIds）
-- 计算 SHA-256 哈希生成，同一个会话内相同参数的多次调用命中同一个 token。
-- 唯一索引保证同一 token 只允许一条有效记录（撤销/完成后不占位）。
-- =============================================================================

SET NAMES utf8mb4;

ALTER TABLE subscription
    ADD COLUMN client_token VARCHAR(64) NULL COMMENT '幂等键（SHA-256 hex，同一参数多次调用命中同一 token）' AFTER biz_no;

-- 部分唯一索引：client_token 非空时唯一，撤销/完成后释放（status IN (0,1) 才占位）
-- MySQL 不支持部分索引，用 generated column + 唯一索引模拟：
-- token_active 只在 status=1（有效）时有值，status 变为 3（撤销）或 2（完成）时置 NULL
ALTER TABLE subscription
    ADD COLUMN token_active VARCHAR(64) GENERATED ALWAYS AS (
        CASE WHEN status = 1 THEN client_token ELSE NULL END
    ) STORED COMMENT '幂等键（仅有效时占位，撤销/完成后释放）';

ALTER TABLE subscription
    ADD UNIQUE INDEX uk_subscription_token_active (token_active);
