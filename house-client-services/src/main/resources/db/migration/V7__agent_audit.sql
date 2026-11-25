-- =============================================================================
-- V7：智能体审计表（P0-5 可还原一次业务动作）
-- 每轮（turn）一行：该用户在该会话第 N 轮问了什么技能、调了什么工具、
-- 参数与结果（脱敏截断）、用的模型、耗时、写操作是否经客户二次确认。
-- 任一认购单可反查：哪一轮、哪些技能、调了什么工具、参数（脱敏）、耗时、是否经确认。
--
-- 写入约定：
--   * 只追加（append-only）：应用层无 UPDATE/DELETE 语句，本表不做逻辑删除；
--   * 同步写表：审计写失败只记 warn 不影响主流程（旁路），"写成功才算 200"的
--     业务写操作（subscription 等）不走本表旁路逻辑；
--   * args / result 落库前必须经过 PiiMasker 脱敏 + 截断，禁止落 PII 明文。
--
-- id 用 BIGINT 雪花 ID 由应用生成（与全库主键约定一致）。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE agent_audit (
    id          BIGINT UNSIGNED NOT NULL COMMENT '审计ID（雪花，应用生成）',
    cid         VARCHAR(64)     NOT NULL COMMENT '会话ID（conversation.id，记忆层 conversationId）',
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '发起用户ID（user_account.id）',
    turn        INT             NOT NULL COMMENT '该会话内的轮次序号（自增，第 N 轮）',
    skills      VARCHAR(255)    NULL     COMMENT '本轮命中技能，逗号分隔（可空=纯寒暄）',
    tool        VARCHAR(255)    NULL     COMMENT '本轮调用的工具，逗号分隔（可空=未调工具）',
    args        TEXT            NULL     COMMENT '工具入参（脱敏+截断），多工具 JSON 拼接',
    result      TEXT            NULL     COMMENT '工具返回值（脱敏+截断），多工具 JSON 拼接',
    model       VARCHAR(64)     NULL     COMMENT '本轮使用的 chat 模型名',
    latency_ms  INT             NULL     COMMENT '本轮端到端耗时（毫秒）',
    confirmed   TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '本轮写操作是否经客户二次确认（P0-4）',
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录时间',
    PRIMARY KEY (id),
    KEY idx_agent_audit_cid (cid, turn),
    KEY idx_agent_audit_user (user_id, created_at),
    KEY idx_agent_audit_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT ='智能体审计（每轮一行，只追加，可还原业务动作）';
