-- =============================================================================
-- V8：技能入库（P1-4）
-- SKILL.md 从 classpath 种子升级为 DB 运行时真相：
--   * DB 是运行时真相，仓库 SKILL.md 是种子
--   * status: draft / active —— 只有 active 的技能会下发给选择器
--   * version: 每次 publish 自动 +1，支持回滚
--   * canary_user_ids / canary_percent: 灰度路由（白名单 + 百分比）
--   * agent_skill_audit: 变更审计，只追加
--
-- 写入约定：
--   * 应用启动时若 DB 无 active 技能，从 classpath SKILL.md 自动种子
--   * 运行时只读 DB，不每轮查库（SkillCatalog 缓存 + 变更事件刷新）
--   * 灰度：canary_user_ids 命中走新版，否则按 canary_percent 百分比路由
--   * 会话首轮锁版本（版本号落会话状态），中途不跳
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE agent_skill (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(64)     NOT NULL COMMENT '技能唯一标识（与 SKILL.md 目录名一致）',
    version     INT             NOT NULL DEFAULT 1 COMMENT '版本号（每次 publish +1）',
    status      VARCHAR(16)     NOT NULL DEFAULT 'active' COMMENT 'draft / active',
    description TEXT            NOT NULL COMMENT '给选择器看的简短描述',
    body        TEXT            NOT NULL COMMENT '流程提示词正文',
    tools       VARCHAR(512)    NOT NULL DEFAULT '' COMMENT '工具名列表，逗号分隔',
    author      VARCHAR(64)     NULL     COMMENT '创建者',
    approver    VARCHAR(64)     NULL     COMMENT '审批人',
    canary_user_ids TEXT        NULL     COMMENT '灰度白名单，逗号分隔的 userId',
    canary_percent  INT         NOT NULL DEFAULT 100 COMMENT '灰度百分比（0-100）',
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    activated_at DATETIME(3)    NULL     COMMENT '最近一次变为 active 的时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill_name_version (name, version),
    KEY idx_skill_status (status, name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT ='智能体技能（DB 运行时真相，SKILL.md 是种子）';

CREATE TABLE agent_skill_audit (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    skill_name  VARCHAR(64)     NOT NULL COMMENT '技能名',
    version     INT             NOT NULL COMMENT '变更后的版本号',
    action      VARCHAR(16)     NOT NULL COMMENT 'create / activate / deactivate / rollback / publish',
    operator    VARCHAR(64)     NOT NULL DEFAULT 'system' COMMENT '操作人',
    detail      TEXT            NULL     COMMENT '变更详情（JSON）',
    created_at  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_skill_audit_name (skill_name, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT ='技能变更审计（只追加）';
