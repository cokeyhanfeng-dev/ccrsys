-- ============================================================
-- 增量 001(2026-09-02):清理 ccr_sys_dept 遗留 dept_code 列
-- 现象:机构树「新增机构」报「系统繁忙,请稍后重试」,后端日志 SQLException:
--   Field 'dept_code' doesn't have a default value
-- 根因:机构编码旧版曾用 dept_code,后统一改 org_code,但库表残留
--   dept_code varchar(32) NOT NULL 无默认值(含 uk_dept_code 唯一键);
--   实体 CcrSysDept/MyBatis insert 不带该列 → MySQL 严格模式报错。
--   仓库建表脚本 db/09_system_dept.sql 本就无此列;全仓库代码无 ccr_sys_dept.dept_code 引用
--   (AssigneeController 等引用的 dept_code 属 ccr_dept_vp,与此列无关)。
-- 已核对存量数据:dept_code 与 org_code 100% 相同(55/55),纯冗余,删列无损。
-- 幂等:information_schema 动态判断——列不存在则跳过,可重复执行。
-- 执行:docker exec -i ccr-mysql mysql -uroot -proot123 < db/incr/20260902_001_remove_dept_code_legacy.sql
-- ============================================================

USE `ccr_rate`;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ccr_sys_dept'
                      AND COLUMN_NAME = 'dept_code');
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ccr_sys_dept'
                      AND INDEX_NAME = 'uk_dept_code');

-- 1. 先删唯一键(索引依赖该列,必须先于删列)
SET @sql1 := IF(@idx_exists > 0,
    'ALTER TABLE ccr_sys_dept DROP INDEX uk_dept_code',
    'SELECT 1');
PREPARE s1 FROM @sql1;
EXECUTE s1;
DEALLOCATE PREPARE s1;

-- 2. 再删列
SET @sql2 := IF(@col_exists > 0,
    'ALTER TABLE ccr_sys_dept DROP COLUMN dept_code',
    'SELECT 1');
PREPARE s2 FROM @sql2;
EXECUTE s2;
DEALLOCATE PREPARE s2;

-- ---------- 自查:列已删则下面返回 0 ----------
SELECT COUNT(*) AS dept_code_col_remaining
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ccr_sys_dept'
  AND COLUMN_NAME = 'dept_code';
