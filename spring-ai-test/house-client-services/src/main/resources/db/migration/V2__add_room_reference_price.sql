-- =============================================================================
-- V2：房屋增加参考单价
-- 认购业务需要写入 unit_price_snap / total_price_snap，价格来源放在房屋主数据上，
-- 避免由大模型凭空给出价格。
-- =============================================================================

ALTER TABLE house_room
    ADD COLUMN reference_unit_price DECIMAL(14, 2) NULL COMMENT '参考单价（元/㎡）' AFTER planned_usage;
