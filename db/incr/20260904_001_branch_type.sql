-- ============================================================
-- 增量 001(2026-09-04):ccr_sys_dept 加支行性质列(branch_type)
-- 需求:支行分综合支行/零售支行两种,零售支行归某综合支行管理(新建零售支行 parent_id 挂到其管理综合支行);
--   流程路由(calcRoute)/审批人解析/审批范围须按支行性质分流(零售申请先零售行长后综合支行长)。
-- 影响表:ccr_sys_dept(仅加列;既有 31 家支行性质留空=综合,无需回填)。
-- 幂等:information_schema 判列存在后执行,可重复运行。
-- 执行:docker exec -i ccr-mysql mysql -uroot -proot123 < db/incr/20260904_001_branch_type.sql(测试库)
-- ============================================================

USE `ccr_rate`;

SET @ddl := IF(
  EXISTS(SELECT 1 FROM information_schema.COLUMNS
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ccr_sys_dept' AND COLUMN_NAME = 'branch_type'),
  'SELECT 1',
  'ALTER TABLE `ccr_sys_dept` ADD COLUMN `branch_type` VARCHAR(16) NULL COMMENT ''支行性质:RETAIL零售/COMPREHENSIVE综合(NULL=综合),仅 BRANCH 机构有意义'' AFTER `org_type`'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 自查:列已存在则返回 1 ----------
SELECT COUNT(*) AS branch_type_col_present
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ccr_sys_dept' AND COLUMN_NAME = 'branch_type';
