-- ============================================================
-- 增量 001(2026-08-29):整单交付改造 - 申请单整单路由字段 + 决议申请维度
-- 需求:审批按整单进行(无分项独立审批),整单链 = 贷款取利率最低分项流程 / 存款原流程
--   (简化版·2026-08-29,plan:整单交付需求)。整单路由权威落库 ccr_application,审批推进以申请单为准;
--   ccr_resolution 增 application_id 支持整单维度决议(保留 pricing_item_id 兼容历史按分项决议)。
-- 幂等:INFORMATION_SCHEMA 判断列存在性后动态 DDL,可重复执行。
-- ============================================================

USE `ccr_rate`;

-- ---------- ccr_application 整单路由字段 ----------
SET @col := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_application' AND COLUMN_NAME = 'route_code');
SET @ddl := IF(@col = 0,
  'ALTER TABLE `ccr_application` ADD COLUMN `route_code` VARCHAR(64) NULL COMMENT ''整单路由编码(终审岗位,提交/推进时冻结)'' AFTER `customer_info_json`',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_application' AND COLUMN_NAME = 'route_chain');
SET @ddl := IF(@col = 0,
  'ALTER TABLE `ccr_application` ADD COLUMN `route_chain` VARCHAR(255) NULL COMMENT ''整单审批链(JSON数组,贷款=利率最低分项,存款=原流程,提交/推进时冻结)'' AFTER `route_code`',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_application' AND COLUMN_NAME = 'start_node_code');
SET @ddl := IF(@col = 0,
  'ALTER TABLE `ccr_application` ADD COLUMN `start_node_code` VARCHAR(64) NULL COMMENT ''整单审批链首节点编码(提交路由后冻结,贷款/存款恒为BRANCH_MANAGER)'' AFTER `route_chain`',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_application' AND COLUMN_NAME = 'current_node_code');
SET @ddl := IF(@col = 0,
  'ALTER TABLE `ccr_application` ADD COLUMN `current_node_code` VARCHAR(64) NULL COMMENT ''整单当前节点编码(审批推进以申请单为准)'' AFTER `start_node_code`',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_application' AND COLUMN_NAME = 'boundary_rate');
SET @ddl := IF(@col = 0,
  'ALTER TABLE `ccr_application` ADD COLUMN `boundary_rate` DECIMAL(9,6) NULL COMMENT ''整单终审节点边界利率(矩阵∩产品硬边界交集,审计溯源)'' AFTER `current_node_code`',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_application' AND COLUMN_NAME = 'matched_matrix_no');
SET @ddl := IF(@col = 0,
  'ALTER TABLE `ccr_application` ADD COLUMN `matched_matrix_no` VARCHAR(64) NULL COMMENT ''整单命中的权限矩阵行编号(审计溯源)'' AFTER `boundary_rate`',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_application' AND COLUMN_NAME = 'dept_code');
SET @ddl := IF(@col = 0,
  'ALTER TABLE `ccr_application` ADD COLUMN `dept_code` VARCHAR(64) NULL COMMENT ''整单路由命中部门编码(节点审批人归属分流)'' AFTER `matched_matrix_no`',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- ccr_resolution 申请维度 ----------
SET @col := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_resolution' AND COLUMN_NAME = 'application_id');
SET @ddl := IF(@col = 0,
  'ALTER TABLE `ccr_resolution` ADD COLUMN `application_id` BIGINT NULL COMMENT ''所属申请主键(整单维度决议;历史按分项决议为空,兼容读取)'' AFTER `resolution_no`',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 索引:整单决议按申请查重 ----------
SET @idx := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_resolution' AND INDEX_NAME = 'idx_resolution_application');
SET @ddl := IF(@idx = 0,
  'ALTER TABLE `ccr_resolution` ADD KEY `idx_resolution_application` (`application_id`)',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- ccr_resolution.pricing_item_id 可空(整单维度决议不落分项,2026-08-29 实测 RESOLUTION_CREATE 插入报错
--   Field ''pricing_item_id'' doesn''t have a default value;历史按分项决议保留该列) ----------
SET @nullable := (SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_resolution' AND COLUMN_NAME = 'pricing_item_id');
SET @ddl := IF(@nullable = 'NO',
  'ALTER TABLE `ccr_resolution` MODIFY COLUMN `pricing_item_id` BIGINT NULL COMMENT ''定价分项ID(整单维度决议为空,历史按分项决议保留)''',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
