-- ============================================================
-- 编号: 20260902_001
-- 日期: 2026-09-02
-- 目的: 申请附件记录 Mini-App-Plus 授信决议来源，并以外部决议+文件ID保证自动带入幂等
-- 影响表: ccr_application_attachment
-- 幂等性: INFORMATION_SCHEMA 判列/索引后执行，可重复运行
-- 执行: mysql -h<主机> -u<用户> -p --default-character-set=utf8mb4 < 本文件
-- ============================================================
USE `ccr_rate`;

SET @ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ccr_application_attachment' AND COLUMN_NAME = 'source_type'),
  'SELECT 1',
  'ALTER TABLE ccr_application_attachment ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT ''MANUAL'' COMMENT ''来源:MANUAL/MINIAPP_CREDIT_RESOLUTION'' AFTER content'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ccr_application_attachment' AND COLUMN_NAME = 'source_business_id'),
  'SELECT 1',
  'ALTER TABLE ccr_application_attachment ADD COLUMN source_business_id VARCHAR(64) NULL COMMENT ''外部决议主键'' AFTER source_type'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ccr_application_attachment' AND COLUMN_NAME = 'source_file_id'),
  'SELECT 1',
  'ALTER TABLE ccr_application_attachment ADD COLUMN source_file_id VARCHAR(64) NULL COMMENT ''外部文件主键'' AFTER source_business_id'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ccr_application_attachment' AND COLUMN_NAME = 'source_resolution_no'),
  'SELECT 1',
  'ALTER TABLE ccr_application_attachment ADD COLUMN source_resolution_no VARCHAR(64) NULL COMMENT ''外部决议编号'' AFTER source_file_id'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ccr_application_attachment' AND INDEX_NAME = 'uk_att_external_file'),
  'SELECT 1',
  'ALTER TABLE ccr_application_attachment ADD UNIQUE KEY uk_att_external_file (application_id, source_type, source_business_id, source_file_id)'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 本变更无 Redis 缓存需要清理。
