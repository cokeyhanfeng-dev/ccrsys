-- 20260824_001 客户信息数仓表增加「管户客户经理」列(mgr_no)
-- 背景:需求①「客户信息数仓推送的表增加一个客户的管户客户经理」。
--      管户客户经理工号参照用户表 ccr_sys_user.username。
--      mgr_no 为空 = 无管户客户经理,任何客户经理(customer_manager)均可从后台拉出该客户;
--      mgr_no 非空 = 有管户客户经理,仅该工号本人可拉出客户信息(搜索联想/详情/业务视图)。
-- 影响表(三张):
--   caps_corp_cust_basic_info        对公客户主数据
--   caps_indv_cust_basic_info        对私客户主数据
--   dw_customer_group_snapshot       集团主数据(决策①纳入,用于展示/后续数仓,权限过滤仅单户客户)
-- 幂等:每段用 information_schema 判断列是否存在,存在则跳过(MySQL 8.0 无 ADD COLUMN IF NOT EXISTS,
--      不能直接用该语法,否则重复执行报 Duplicate column)。PREPARE/EXECUTE 动态执行,可重复跑。
-- 兼容性:不依赖会话变量,每段自包含,支持 mysql 客户端/Navicat/DBeaver。
-- 无 Redis key 清理(纯 DDL,执行完即生效,无需重启后端)。
-- 执行命令:docker exec -i ccr-mysql mysql -uroot -proot123 --default-character-set=utf8mb4 < db/incr/20260824_001_customer_mgr_no.sql

USE ccr_rate;

-- ① 对公客户:加 mgr_no(管户客户经理工号)
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'caps_corp_cust_basic_info' AND COLUMN_NAME = 'mgr_no'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE caps_corp_cust_basic_info ADD COLUMN mgr_no VARCHAR(32) NULL COMMENT ''管户客户经理工号(参照 ccr_sys_user.username;空=无管户,所有客户经理可见)'' AFTER cust_class',
  'SELECT ''caps_corp_cust_basic_info.mgr_no 已存在,跳过'' AS info');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ② 对私客户:加 mgr_no
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'caps_indv_cust_basic_info' AND COLUMN_NAME = 'mgr_no'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE caps_indv_cust_basic_info ADD COLUMN mgr_no VARCHAR(32) NULL COMMENT ''管户客户经理工号(参照 ccr_sys_user.username;空=无管户,所有客户经理可见)'' AFTER cust_class',
  'SELECT ''caps_indv_cust_basic_info.mgr_no 已存在,跳过'' AS info');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ③ 集团主数据:加 mgr_no(决策①纳入)
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'dw_customer_group_snapshot' AND COLUMN_NAME = 'mgr_no'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE dw_customer_group_snapshot ADD COLUMN mgr_no VARCHAR(32) NULL COMMENT ''管户客户经理工号(参照 ccr_sys_user.username;空=无管户,所有客户经理可见)''',
  'SELECT ''dw_customer_group_snapshot.mgr_no 已存在,跳过'' AS info');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ④ 验证:三表均应出现 mgr_no 列
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'ccr_rate'
  AND TABLE_NAME IN ('caps_corp_cust_basic_info', 'caps_indv_cust_basic_info', 'dw_customer_group_snapshot')
  AND COLUMN_NAME = 'mgr_no'
ORDER BY TABLE_NAME;
