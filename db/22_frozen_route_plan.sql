-- ============================================================
-- 提交冻结完整路由计划：产品链路、节点权限、产品硬边界
-- ============================================================

USE `ccr_rate`;

DROP PROCEDURE IF EXISTS `ccr_add_frozen_route_plan`;
DELIMITER $$
CREATE PROCEDURE `ccr_add_frozen_route_plan`()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_pricing_item'
      AND COLUMN_NAME = 'hard_boundary_rate'
  ) THEN
    ALTER TABLE `ccr_pricing_item`
      ADD COLUMN `hard_boundary_rate` DECIMAL(9,6) NULL
        COMMENT '产品硬边界利率；提交冻结，后续调价沿用'
        AFTER `boundary_rate`;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_pricing_item'
      AND COLUMN_NAME = 'product_route_id'
  ) THEN
    ALTER TABLE `ccr_pricing_item`
      ADD COLUMN `product_route_id` BIGINT NULL
        COMMENT '提交冻结的产品审批链路主键'
        AFTER `dept_code`;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_pricing_item'
      AND COLUMN_NAME = 'product_route_version'
  ) THEN
    ALTER TABLE `ccr_pricing_item`
      ADD COLUMN `product_route_version` INT NULL
        COMMENT '提交冻结的产品审批链路业务版本号'
        AFTER `product_route_id`;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_pricing_item'
      AND COLUMN_NAME = 'route_mode'
  ) THEN
    ALTER TABLE `ccr_pricing_item`
      ADD COLUMN `route_mode` VARCHAR(16) NULL
        COMMENT '冻结路由模式 CHAINED/DIRECT_VOTE'
        AFTER `product_route_version`;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_pricing_item'
      AND COLUMN_NAME = 'route_chain_json'
  ) THEN
    ALTER TABLE `ccr_pricing_item`
      ADD COLUMN `route_chain_json` JSON NULL
        COMMENT '完整执行链路 JSON；审批推进只消费冻结值'
        AFTER `route_mode`;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_pricing_item'
      AND COLUMN_NAME = 'node_permission_json'
  ) THEN
    ALTER TABLE `ccr_pricing_item`
      ADD COLUMN `node_permission_json` JSON NULL
        COMMENT '各节点冻结权限边界 JSON；ANY 表示无边界即可终审'
        AFTER `route_chain_json`;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_pricing_item'
      AND COLUMN_NAME = 'president_required'
  ) THEN
    ALTER TABLE `ccr_pricing_item`
      ADD COLUMN `president_required` CHAR(1) NULL
        COMMENT '是否需要行长决策 Y/N；提交冻结'
        AFTER `node_permission_json`;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_pricing_item'
      AND COLUMN_NAME = 'flow_key'
  ) THEN
    ALTER TABLE `ccr_pricing_item`
      ADD COLUMN `flow_key` VARCHAR(64) NULL
        COMMENT '冻结的流程定义编码'
        AFTER `president_required`;
  END IF;

  -- 历史分项无法还原提交当时的逐节点矩阵值，保留 node_permission_json/hard_boundary_rate 为空，
  -- 由应用兼容逻辑读取旧配置；完整链路按旧状态机回填，避免升级后中断在途流程。
  UPDATE `ccr_pricing_item`
  SET `route_chain_json` = COALESCE(`route_chain_json`, CASE
          WHEN `pricing_carrier_type` = 'DEPOSIT_ACCOUNT'
            THEN JSON_ARRAY('BRANCH_MANAGER', 'SIX_PEOPLE_GROUP', 'PRESIDENT')
          ELSE JSON_ARRAY('BRANCH_MANAGER', 'DEPT_GENERAL_MANAGER', 'VICE_PRESIDENT',
                          'SIX_PEOPLE_GROUP', 'PRESIDENT')
        END),
      `route_mode` = COALESCE(`route_mode`, 'CHAINED'),
      `president_required` = COALESCE(`president_required`, 'Y'),
      `flow_key` = COALESCE(`flow_key`, 'rate_approval')
  WHERE `route_chain_json` IS NULL OR `route_mode` IS NULL
     OR `president_required` IS NULL OR `flow_key` IS NULL;
END$$
DELIMITER ;

CALL `ccr_add_frozen_route_plan`();
DROP PROCEDURE `ccr_add_frozen_route_plan`;
