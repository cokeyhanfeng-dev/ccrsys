-- ============================================================
-- 增量 002(2026-08-25):集团客户区分国企/非国企
-- 需求:集团本身区分「国企集团/非国企集团」,属性属集团本身(非旗下企业)。
--   数仓集团主数据 dw_customer_group_snapshot + 手工集团 ccr_group 各加 state_owned_flag 列。
-- 幂等:information_schema 检查列存在,不存在才 ALTER,可重复执行。
-- ============================================================

USE `ccr_rate`;

-- ---------- 数仓集团主数据快照加列 ----------
DROP PROCEDURE IF EXISTS `ccr_add_group_state_owned_dw`;
DELIMITER $$
CREATE PROCEDURE `ccr_add_group_state_owned_dw`()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA='ccr_rate' AND TABLE_NAME='dw_customer_group_snapshot'
                   AND COLUMN_NAME='state_owned_flag') THEN
    ALTER TABLE `dw_customer_group_snapshot`
      ADD COLUMN `state_owned_flag` CHAR(1) NULL
      COMMENT '国企集团Y/N(集团本身属性,非旗下企业;2026-08-25)';
  END IF;
END$$
DELIMITER ;
CALL `ccr_add_group_state_owned_dw`();
DROP PROCEDURE `ccr_add_group_state_owned_dw`;

-- ---------- 手工集团主数据加列 ----------
DROP PROCEDURE IF EXISTS `ccr_add_group_state_owned_manual`;
DELIMITER $$
CREATE PROCEDURE `ccr_add_group_state_owned_manual`()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA='ccr_rate' AND TABLE_NAME='ccr_group'
                   AND COLUMN_NAME='state_owned_flag') THEN
    ALTER TABLE `ccr_group`
      ADD COLUMN `state_owned_flag` CHAR(1) NULL
      COMMENT '国企集团Y/N(集团本身属性,非旗下企业;2026-08-25)';
  END IF;
END$$
DELIMITER ;
CALL `ccr_add_group_state_owned_manual`();
DROP PROCEDURE `ccr_add_group_state_owned_manual`;
