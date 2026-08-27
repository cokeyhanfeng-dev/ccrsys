-- ============================================================
-- 增量 002(2026-08-26):他行融资概要增加「报告日期」(征信报告日期)
-- 需求:融资情况/他行融资概要增加「报告日期」字段,为数仓征信报告日期
--   (dw_credit_report_snapshot.report_date)带出,随单快照持久化展示(§2026-08-26)。
-- 幂等:IF NOT EXISTS 包裹 ADD COLUMN,可重复执行。
-- ============================================================

USE `ccr_rate`;

SET @report_date_col := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_application_credit_summary'
    AND COLUMN_NAME = 'report_date');

SET @ddl := IF(@report_date_col = 0,
  'ALTER TABLE `ccr_application_credit_summary` ADD COLUMN `report_date` DATE NULL COMMENT ''征信报告日期(数仓带出)'' AFTER `external_guarantee_balance`',
  'SELECT 1');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
