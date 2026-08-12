-- ============================================================
-- 申请承诺截止日期：修复实体字段与初始化表结构不一致
-- ============================================================

USE `ccr_rate`;

DROP PROCEDURE IF EXISTS `ccr_add_application_commitment_end_date`;
DELIMITER $$
CREATE PROCEDURE `ccr_add_application_commitment_end_date`()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = 'ccr_rate'
                   AND TABLE_NAME = 'ccr_application_commitment'
                   AND COLUMN_NAME = 'end_date') THEN
    ALTER TABLE `ccr_application_commitment`
      ADD COLUMN `end_date` DATE NULL
      COMMENT '拟达成贡献度承诺完成截止日期(§7.1)'
      AFTER `commitment_desc`;
  END IF;
END$$
DELIMITER ;

CALL `ccr_add_application_commitment_end_date`();
DROP PROCEDURE `ccr_add_application_commitment_end_date`;
