-- =============================================================================
-- house-client-services 数据库初始化脚本
-- MySQL 8.0+ / InnoDB / utf8mb4
--
-- 设计约定：
-- 1. 所有主键均为应用层生成的雪花 ID（MyBatis-Plus IdType.ASSIGN_ID），
--    不使用 AUTO_INCREMENT；类型统一 BIGINT UNSIGNED。
--    JSON 序列化时必须转 String，避免前端 JS 精度丢失。
-- 2. 主数据（org_unit / person / house_project / house_building / house_room）
--    保存“当前值”，只做逻辑删除。
-- 3. 业务凭证（subscription 及其 3 张关联表）保存“签约当时快照”，生效后不可变：
--    - *_id          外键，用于溯源、联表统计
--    - *_snap        快照列，用于凭证/合同/详情页展示
--    两者并存，口径不一致是预期行为。
-- 4. 外键统一 ON DELETE RESTRICT，禁止级联删除业务凭证。
-- 5. 逻辑删除用 deleted_flag（0=正常，删除时置为主键值），
--    使唯一索引既能约束在存数据，又允许删除后重建。
-- 6. 表 chat_memory 由 Spring AI 的 JdbcChatMemoryRepository 自行初始化，
--    不在本脚本范围内。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 1. 单位（开发企业是单位的一种）
-- -----------------------------------------------------------------------------
CREATE TABLE org_unit (
    id           BIGINT UNSIGNED NOT NULL COMMENT '单位ID（雪花）',
    unit_type    TINYINT         NOT NULL DEFAULT 1 COMMENT '单位类型：1开发企业 2物业 3中介 9其他',
    full_name    VARCHAR(200)    NOT NULL COMMENT '单位全称',
    id_no        VARCHAR(64)     NOT NULL COMMENT '证件号码（统一社会信用代码）',
    contact      VARCHAR(64)     NULL     COMMENT '单位联系电话',
    created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '建立时间',
    updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_flag BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常，非0=已删除（值为自身ID）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_unit_id_no (id_no, deleted_flag),
    KEY idx_unit_name (full_name),
    KEY idx_unit_type (unit_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT ='单位（开发企业等）';

-- -----------------------------------------------------------------------------
-- 2. 个人
--    用户名/密码已拆到 user_account，不是每个自然人都有登录账号。
-- -----------------------------------------------------------------------------
CREATE TABLE person (
    id           BIGINT UNSIGNED NOT NULL COMMENT '个人ID（雪花）',
    full_name    VARCHAR(100)    NOT NULL COMMENT '个人全称',
    id_type      TINYINT         NOT NULL DEFAULT 1 COMMENT '证件类别：1身份证 2护照 3港澳台居民证 9其他',
    id_no        VARCHAR(64)     NOT NULL COMMENT '证件号码（敏感信息，建议后续加密）',
    status       TINYINT         NOT NULL DEFAULT 1 COMMENT '状态：1正常 0停用',
    created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '建立时间',
    updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_flag BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_person_id_no (id_type, id_no, deleted_flag),
    KEY idx_person_name (full_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT ='个人（主数据）';

-- -----------------------------------------------------------------------------
-- 3. 登录账号（个人账号与单位账号共表）
-- -----------------------------------------------------------------------------
CREATE TABLE user_account (
    id            BIGINT UNSIGNED NOT NULL COMMENT '账号ID（雪花）',
    person_id     BIGINT UNSIGNED NULL COMMENT '个人账号 -> person.id',
    org_id        BIGINT UNSIGNED NULL COMMENT '单位账号 -> org_unit.id',
    username      VARCHAR(64)     NOT NULL COMMENT '用户名',
    password_hash VARCHAR(100)    NOT NULL COMMENT '密码哈希（BCrypt），禁止存明文',
    status        TINYINT         NOT NULL DEFAULT 1 COMMENT '状态：1启用 0禁用',
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '建立时间',
    updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_flag  BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_username (username, deleted_flag),
    KEY idx_account_person (person_id),
    KEY idx_account_org (org_id),
    CONSTRAINT fk_account_person FOREIGN KEY (person_id) REFERENCES person (id) ON DELETE RESTRICT,
    CONSTRAINT fk_account_org FOREIGN KEY (org_id) REFERENCES org_unit (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT ='登录账号';

-- -----------------------------------------------------------------------------
-- 4. 联系方式（个人 1:N 联系方式）
-- -----------------------------------------------------------------------------
CREATE TABLE person_contact (
    id            BIGINT UNSIGNED NOT NULL COMMENT '联系方式ID（雪花）',
    person_id     BIGINT UNSIGNED NOT NULL COMMENT '个人ID',
    contact_type  TINYINT         NOT NULL DEFAULT 1 COMMENT '类别：1手机 2固定电话 3邮箱 4微信 9其他',
    contact_value VARCHAR(128)    NOT NULL COMMENT '联系电话/号码',
    is_primary    TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否主联系方式',
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '建立时间',
    updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_flag  BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_contact (person_id, contact_type, contact_value, deleted_flag),
    KEY idx_contact_person (person_id),
    CONSTRAINT fk_contact_person FOREIGN KEY (person_id) REFERENCES person (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT ='联系方式';

-- -----------------------------------------------------------------------------
-- 5. 项目（一个项目属于一个开发企业）
-- -----------------------------------------------------------------------------
CREATE TABLE house_project (
    id           BIGINT UNSIGNED NOT NULL COMMENT '项目ID（雪花）',
    project_name VARCHAR(200)    NOT NULL COMMENT '项目名称',
    location     VARCHAR(300)    NOT NULL COMMENT '项目坐落',
    developer_id BIGINT UNSIGNED NOT NULL COMMENT '开发企业ID -> org_unit.id',
    created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '建立时间',
    updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_flag BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_project (developer_id, project_name, deleted_flag),
    KEY idx_project_developer (developer_id),
    CONSTRAINT fk_project_developer FOREIGN KEY (developer_id) REFERENCES org_unit (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT ='项目';

-- -----------------------------------------------------------------------------
-- 6. 幢（项目 1:N 幢）
-- -----------------------------------------------------------------------------
CREATE TABLE house_building (
    id           BIGINT UNSIGNED NOT NULL COMMENT '幢ID（雪花）',
    project_id   BIGINT UNSIGNED NOT NULL COMMENT '项目ID',
    building_no  VARCHAR(50)     NOT NULL COMMENT '幢号',
    location     VARCHAR(300)    NOT NULL COMMENT '幢坐落',
    created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '建立时间',
    updated_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_flag BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_building (project_id, building_no, deleted_flag),
    KEY idx_building_project (project_id),
    CONSTRAINT fk_building_project FOREIGN KEY (project_id) REFERENCES house_project (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT ='幢';

-- -----------------------------------------------------------------------------
-- 7. 房屋（幢 1:N 房屋）
--    active_subscription_id 用于实现“一房同时只能有一条有效认购”，
--    通过 UPDATE ... WHERE active_subscription_id IS NULL 抢占，唯一索引兜底并发。
-- -----------------------------------------------------------------------------
CREATE TABLE house_room (
    id                     BIGINT UNSIGNED NOT NULL COMMENT '房屋ID（雪花）',
    building_id            BIGINT UNSIGNED NOT NULL COMMENT '幢ID',
    room_no                VARCHAR(50)     NOT NULL COMMENT '房号',
    full_address           VARCHAR(400)    NOT NULL COMMENT '房屋坐落（幢坐落 + 房号）',
    building_area          DECIMAL(10, 2)  NULL COMMENT '建筑面积（㎡）',
    inner_area             DECIMAL(10, 2)  NULL COMMENT '套内建筑面积（㎡）',
    shared_area            DECIMAL(10, 2)  NULL COMMENT '分摊建筑面积（㎡）',
    planned_usage          TINYINT         NULL COMMENT '规划用途：1住宅 2商业 3办公 4车位 9其他',
    active_subscription_id BIGINT UNSIGNED NULL COMMENT '当前有效认购ID（空=可售）',
    created_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '建立时间',
    updated_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_flag           BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_room (building_id, room_no, deleted_flag),
    UNIQUE KEY uk_room_active_subscription (active_subscription_id),
    KEY idx_room_building (building_id),
    KEY idx_room_address (full_address(100)),
    CONSTRAINT fk_room_building FOREIGN KEY (building_id) REFERENCES house_building (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT ='房屋';

-- -----------------------------------------------------------------------------
-- 8. 认购业务（主表只放业务自身属性，人员/房屋/单位信息走关联表 + 快照）
-- -----------------------------------------------------------------------------
CREATE TABLE subscription (
    id            BIGINT UNSIGNED NOT NULL COMMENT '业务号（雪花ID）',
    biz_no        VARCHAR(32)     NOT NULL COMMENT '对外业务编号',
    status        TINYINT         NOT NULL DEFAULT 0 COMMENT '状态：0草稿 1有效 2完成 3撤销',
    biz_type      TINYINT         NOT NULL DEFAULT 1 COMMENT '业务类型：1认购 2签约 9其他',
    total_price   DECIMAL(18, 2)  NOT NULL DEFAULT 0.00 COMMENT '总价',
    unit_price    DECIMAL(14, 2)  NOT NULL DEFAULT 0.00 COMMENT '均价（明细见订阅房屋快照）',
    created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '业务建立时间',
    finished_at   DATETIME(3)     NULL     COMMENT '业务完成时间',
    cancelled_at  DATETIME(3)     NULL     COMMENT '撤销时间',
    cancel_reason VARCHAR(200)    NULL     COMMENT '撤销原因',
    created_by    BIGINT UNSIGNED NULL     COMMENT '创建人账号ID',
    updated_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version       INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_subscription_biz_no (biz_no),
    KEY idx_subscription_status_time (status, created_at),
    KEY idx_subscription_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT ='认购业务';

-- -----------------------------------------------------------------------------
-- 9. 认购人（认购业务 N:M 个人）
--    快照列在认购创建事务内一次性复制，之后永不刷新。
-- -----------------------------------------------------------------------------
CREATE TABLE subscription_person (
    id               BIGINT UNSIGNED NOT NULL COMMENT '关联ID（雪花）',
    subscription_id  BIGINT UNSIGNED NOT NULL COMMENT '业务号 -> subscription.id',
    person_id        BIGINT UNSIGNED NOT NULL COMMENT '个人ID -> person.id（溯源键）',
    buyer_type       TINYINT         NOT NULL DEFAULT 1 COMMENT '购买主体类型：1个人 2单位（预留）',
    is_primary       TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否主认购人',
    share_ratio      DECIMAL(5, 4)   NULL     COMMENT '产权份额（0.0000~1.0000）',
    person_name_snap VARCHAR(100)    NOT NULL COMMENT '认购时姓名快照',
    id_type_snap     TINYINT         NOT NULL COMMENT '认购时证件类别',
    id_no_snap       VARCHAR(64)     NOT NULL COMMENT '认购时证件号码',
    contact_snap     VARCHAR(64)     NULL     COMMENT '认购时联系电话',
    snapshot_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '快照时间',
    status           TINYINT         NOT NULL DEFAULT 1 COMMENT '1有效 0已移除',
    created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sub_person (subscription_id, person_id),
    KEY idx_sub_person_person (person_id),
    CONSTRAINT fk_sub_person_sub FOREIGN KEY (subscription_id) REFERENCES subscription (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sub_person_person FOREIGN KEY (person_id) REFERENCES person (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT ='认购人（含快照）';

-- -----------------------------------------------------------------------------
-- 10. 认购房屋（认购业务 N:M 房屋）
-- -----------------------------------------------------------------------------
CREATE TABLE subscription_house (
    id                  BIGINT UNSIGNED NOT NULL COMMENT '关联ID（雪花）',
    subscription_id     BIGINT UNSIGNED NOT NULL COMMENT '业务号 -> subscription.id',
    house_id            BIGINT UNSIGNED NOT NULL COMMENT '房屋ID -> house_room.id（溯源键）',
    is_primary          TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否主房屋',
    house_location_snap VARCHAR(400)    NOT NULL COMMENT '认购时完整坐落',
    building_no_snap    VARCHAR(50)     NULL     COMMENT '认购时幢号',
    room_no_snap        VARCHAR(50)     NOT NULL COMMENT '认购时房号',
    building_area_snap  DECIMAL(10, 2)  NULL     COMMENT '认购时建筑面积',
    inner_area_snap     DECIMAL(10, 2)  NULL     COMMENT '认购时套内面积',
    shared_area_snap    DECIMAL(10, 2)  NULL     COMMENT '认购时分摊面积',
    unit_price_snap     DECIMAL(14, 2)  NULL     COMMENT '认购时单价',
    total_price_snap    DECIMAL(18, 2)  NULL     COMMENT '认购时该房屋总价',
    project_name_snap   VARCHAR(200)    NULL     COMMENT '认购时项目名称',
    developer_name_snap VARCHAR(200)    NULL     COMMENT '认购时开发企业名称',
    snapshot_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '快照时间',
    status              TINYINT         NOT NULL DEFAULT 1 COMMENT '1有效 0已移除',
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sub_house (subscription_id, house_id),
    KEY idx_sub_house_house (house_id),
    CONSTRAINT fk_sub_house_sub FOREIGN KEY (subscription_id) REFERENCES subscription (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sub_house_house FOREIGN KEY (house_id) REFERENCES house_room (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT ='认购房屋（含快照）';

-- -----------------------------------------------------------------------------
-- 11. 认购单位（认购业务 N:M 单位，企业名义购房）
-- -----------------------------------------------------------------------------
CREATE TABLE subscription_org (
    id               BIGINT UNSIGNED NOT NULL COMMENT '关联ID（雪花）',
    subscription_id  BIGINT UNSIGNED NOT NULL COMMENT '业务号 -> subscription.id',
    org_id           BIGINT UNSIGNED NOT NULL COMMENT '单位ID -> org_unit.id（溯源键）',
    is_primary       TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否主购买主体',
    share_ratio      DECIMAL(5, 4)   NULL     COMMENT '产权份额',
    unit_name_snap   VARCHAR(200)    NOT NULL COMMENT '认购时单位全称',
    unit_id_no_snap  VARCHAR(64)     NOT NULL COMMENT '认购时单位证件号码',
    contact_snap     VARCHAR(64)     NULL     COMMENT '认购时联系电话',
    snapshot_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '快照时间',
    status           TINYINT         NOT NULL DEFAULT 1 COMMENT '1有效 0已移除',
    created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sub_org (subscription_id, org_id),
    KEY idx_sub_org_org (org_id),
    CONSTRAINT fk_sub_org_sub FOREIGN KEY (subscription_id) REFERENCES subscription (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sub_org_org FOREIGN KEY (org_id) REFERENCES org_unit (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT ='认购单位（含快照）';

-- -----------------------------------------------------------------------------
-- 12. 补建房屋 -> 当前认购 的循环外键（subscription 建表在 house_room 之后）
-- -----------------------------------------------------------------------------
ALTER TABLE house_room
    ADD CONSTRAINT fk_room_active_subscription
        FOREIGN KEY (active_subscription_id) REFERENCES subscription (id) ON DELETE SET NULL;

-- =============================================================================
-- 应用层必须保证、数据库无法表达的业务约束：
-- 1. 一个认购业务至少有一个购买主体（subscription_person 或 subscription_org 非空），
--    且至多一个 is_primary = 1。
-- 2. 认购状态进入 1（有效）后，业务字段与快照列只读；
--    改人/改房只能在 0（草稿）状态进行。
-- 3. 认购生效时：先抢占 house_room.active_subscription_id，再写 subscription_house，
--    两件事必须在同一 @Transactional 内完成。
-- 4. 移除认购房屋（subscription_house.status = 0）或撤销业务时，
--    必须同步把对应 house_room.active_subscription_id 置回 NULL。
-- 5. 撤销 = status 置 3 + cancelled_at + 释放房屋，禁止物理删除业务凭证。
-- 6. 单价/总价与面积的关系由服务端校验，数据库不做强制。
-- =============================================================================
