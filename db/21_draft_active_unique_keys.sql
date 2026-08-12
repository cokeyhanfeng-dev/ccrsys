-- ============================================================
-- 草稿子表逻辑删除唯一键：仅活动行唯一，历史版本可重复保留
-- ============================================================

USE `ccr_rate`;

DROP PROCEDURE IF EXISTS `ccr_fix_draft_active_unique_keys`;
DELIMITER $$
CREATE PROCEDURE `ccr_fix_draft_active_unique_keys`()
BEGIN
  DECLARE duplicate_count BIGINT DEFAULT 0;

  -- 升级前拒绝已有活动重复，避免通过删除数据来迁就新约束。
  SELECT COUNT(*) INTO duplicate_count
  FROM (
    SELECT `application_id`, `member_customer_no`
    FROM `ccr_application_member`
    WHERE `del_flag` = '0'
    GROUP BY `application_id`, `member_customer_no`
    HAVING COUNT(*) > 1
  ) duplicated;
  IF duplicate_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'ccr_application_member 存在活动成员重复，请先核查数据';
  END IF;

  SELECT COUNT(*) INTO duplicate_count
  FROM (
    SELECT `application_id`, `contract_business_key`
    FROM `ccr_pricing_item_contract_rel`
    WHERE `del_flag` = '0'
    GROUP BY `application_id`, `contract_business_key`
    HAVING COUNT(*) > 1
  ) duplicated;
  IF duplicate_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'ccr_pricing_item_contract_rel 存在活动合同重复，请先核查数据';
  END IF;

  SELECT COUNT(*) INTO duplicate_count
  FROM (
    SELECT `application_id`, `deposit_account_hash`
    FROM `ccr_pricing_item_deposit_rel`
    WHERE `del_flag` = '0' AND `deposit_account_hash` IS NOT NULL
    GROUP BY `application_id`, `deposit_account_hash`
    HAVING COUNT(*) > 1
  ) duplicated;
  IF duplicate_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'ccr_pricing_item_deposit_rel 存在活动账户重复，请先核查数据';
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_application_member'
      AND COLUMN_NAME = 'active_application_id'
  ) THEN
    ALTER TABLE `ccr_application_member`
      ADD COLUMN `active_application_id` BIGINT
        GENERATED ALWAYS AS (CASE WHEN `del_flag` = '0' THEN `application_id` ELSE NULL END) STORED
        COMMENT '活动行申请ID；历史行为空，供唯一键使用'
        AFTER `member_role`;
  END IF;
  IF EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_application_member'
      AND INDEX_NAME = 'uk_app_member'
  ) THEN
    ALTER TABLE `ccr_application_member` DROP INDEX `uk_app_member`;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_application_member'
      AND INDEX_NAME = 'uk_active_app_member'
  ) THEN
    ALTER TABLE `ccr_application_member`
      ADD UNIQUE KEY `uk_active_app_member` (`active_application_id`, `member_customer_no`);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_pricing_item_contract_rel'
      AND COLUMN_NAME = 'active_application_id'
  ) THEN
    ALTER TABLE `ccr_pricing_item_contract_rel`
      ADD COLUMN `active_application_id` BIGINT
        GENERATED ALWAYS AS (CASE WHEN `del_flag` = '0' THEN `application_id` ELSE NULL END) STORED
        COMMENT '活动行申请ID；历史行为空，供唯一键使用'
        AFTER `planned_contract_flag`;
  END IF;
  IF EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_pricing_item_contract_rel'
      AND INDEX_NAME = 'uk_app_contract'
  ) THEN
    ALTER TABLE `ccr_pricing_item_contract_rel` DROP INDEX `uk_app_contract`;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_pricing_item_contract_rel'
      AND INDEX_NAME = 'uk_active_app_contract'
  ) THEN
    ALTER TABLE `ccr_pricing_item_contract_rel`
      ADD UNIQUE KEY `uk_active_app_contract` (`active_application_id`, `contract_business_key`);
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_pricing_item_deposit_rel'
      AND COLUMN_NAME = 'active_application_id'
  ) THEN
    ALTER TABLE `ccr_pricing_item_deposit_rel`
      ADD COLUMN `active_application_id` BIGINT
        GENERATED ALWAYS AS (CASE WHEN `del_flag` = '0' THEN `application_id` ELSE NULL END) STORED
        COMMENT '活动行申请ID；历史行为空，供唯一键使用'
        AFTER `planned_account_flag`;
  END IF;
  IF EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_pricing_item_deposit_rel'
      AND INDEX_NAME = 'uk_app_account'
  ) THEN
    ALTER TABLE `ccr_pricing_item_deposit_rel` DROP INDEX `uk_app_account`;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_pricing_item_deposit_rel'
      AND INDEX_NAME = 'uk_active_app_account'
  ) THEN
    ALTER TABLE `ccr_pricing_item_deposit_rel`
      ADD UNIQUE KEY `uk_active_app_account` (`active_application_id`, `deposit_account_hash`);
  END IF;
END$$
DELIMITER ;

CALL `ccr_fix_draft_active_unique_keys`();
DROP PROCEDURE `ccr_fix_draft_active_unique_keys`;
