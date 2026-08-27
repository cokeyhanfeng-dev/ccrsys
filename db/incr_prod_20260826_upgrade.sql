-- ============================================================
-- 生产增量升级脚本 incr_prod_20260826_upgrade.sql
-- 适用:生产库当前为 022 版本(08-21),升级至 2026-08-26 最新态
-- 组成:20260821_004 ~ 20260825_004 全部增量(剔除模拟段) + DDL 修复附录
-- 幂等:全部含信息_schema/IF NOT EXISTS/NOT EXISTS/INSERT IGNORE 保护,可重复执行
-- 不含:任何 DROP / 删列 / 改数据类型为破坏性之外的操作(etl_md5 改 VARCHAR 为口径修复)
-- 执行:mysql --default-character-set=utf8mb4 -u<user> -p ccr_rate < incr_prod_20260826_upgrade.sql
-- 敏感:本文件为 DDL+初始化数据,不涉真实账号,可入库;生产执行前先备份
-- 详参:docs/25_生产全量部署手册.md 增量升级章节
-- ============================================================

-- 20260821_004 warm-flow 标准流程跳转关系补全(flow_skip)
-- 背景:生产「利率审批标准流程」(flow_code=rate_approval)审批到支行行长下一步报
--      NodeServiceImpl.lambda$getNextNode NPE。根因:flow_definition 节点表有 7 个节点
--      (start→支行行长→部门总经理→分管副行长→六人小组→总行行长→end),但跳转关系表
--      flow_skip 为空(旧版初始化只建节点没建跳转),warm-flow 跳转时查不到下一步→NPE。
-- 影响表:flow_skip(INSERT 6 条逐级上送 PASS 跳转)
-- 幂等:每条 INSERT 带 NOT EXISTS 保护,重复执行不会重复插入。
-- 兼容性:
--   ① id 用固定安全值(9000000000000000001~0006,均在 BIGINT 有符号范围内)。
--      ⚠️ 勿改用 UUID_SHORT():其返回值可为无符号 64 位超 BIGINT 上限,生产实测报
--         "Out of range value for column 'id'"。
--   ② definition_id 用子查询按 flow_code 自动取,无需手工替换。
--   ③ 不依赖会话变量,每段自包含,支持 mysql 客户端/Navicat/DBeaver。
-- 若生产 flow_code 不叫 rate_approval:把脚本内两处 'rate_approval' 换成实际值再执行。
-- 无 Redis key 清理(flow_skip 无缓存,warm-flow 每次跳转实时查库,插完即生效,无需重启后端)。
-- 执行命令:docker exec -i ccr-mysql mysql -uroot -proot123 --default-character-set=utf8mb4 < db/incr/20260821_004_flow_skip_standard_chain.sql

USE ccr_rate;

-- ① 诊断:确认流程定义存在(正常应返回一条 rate_approval)
SELECT id, flow_code, flow_name, version, is_publish
FROM flow_definition
WHERE flow_code = 'rate_approval';

-- ② 检查该流程当前跳转数(正常为 0;已非 0 则 ③ 会被 NOT EXISTS 全部跳过)
SELECT COUNT(*) AS skip_cnt
FROM flow_skip
WHERE definition_id = (SELECT id FROM flow_definition WHERE flow_code='rate_approval' AND is_publish=1 LIMIT 1);

-- ③ 插入 6 条逐级上送跳转(每条独立幂等,可重复执行)
INSERT INTO flow_skip
  (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, create_time, create_by, update_time, update_by, del_flag)
SELECT 9000000000000000001, d.id, 'start', 0, 'BRANCH_MANAGER', 1, '通过', 'PASS', NULL, NOW(), 'admin', NOW(), 'admin', '0'
FROM (SELECT id FROM flow_definition WHERE flow_code='rate_approval' AND is_publish=1 LIMIT 1) d
WHERE NOT EXISTS (SELECT 1 FROM flow_skip s WHERE s.definition_id = d.id AND s.now_node_code='start' AND s.next_node_code='BRANCH_MANAGER');

INSERT INTO flow_skip
  (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, create_time, create_by, update_time, update_by, del_flag)
SELECT 9000000000000000002, d.id, 'BRANCH_MANAGER', 1, 'DEPT_GENERAL_MANAGER', 1, '通过', 'PASS', NULL, NOW(), 'admin', NOW(), 'admin', '0'
FROM (SELECT id FROM flow_definition WHERE flow_code='rate_approval' AND is_publish=1 LIMIT 1) d
WHERE NOT EXISTS (SELECT 1 FROM flow_skip s WHERE s.definition_id = d.id AND s.now_node_code='BRANCH_MANAGER' AND s.next_node_code='DEPT_GENERAL_MANAGER');

INSERT INTO flow_skip
  (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, create_time, create_by, update_time, update_by, del_flag)
SELECT 9000000000000000003, d.id, 'DEPT_GENERAL_MANAGER', 1, 'VICE_PRESIDENT', 1, '通过', 'PASS', NULL, NOW(), 'admin', NOW(), 'admin', '0'
FROM (SELECT id FROM flow_definition WHERE flow_code='rate_approval' AND is_publish=1 LIMIT 1) d
WHERE NOT EXISTS (SELECT 1 FROM flow_skip s WHERE s.definition_id = d.id AND s.now_node_code='DEPT_GENERAL_MANAGER' AND s.next_node_code='VICE_PRESIDENT');

INSERT INTO flow_skip
  (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, create_time, create_by, update_time, update_by, del_flag)
SELECT 9000000000000000004, d.id, 'VICE_PRESIDENT', 1, 'SIX_PEOPLE_GROUP', 1, '通过', 'PASS', NULL, NOW(), 'admin', NOW(), 'admin', '0'
FROM (SELECT id FROM flow_definition WHERE flow_code='rate_approval' AND is_publish=1 LIMIT 1) d
WHERE NOT EXISTS (SELECT 1 FROM flow_skip s WHERE s.definition_id = d.id AND s.now_node_code='VICE_PRESIDENT' AND s.next_node_code='SIX_PEOPLE_GROUP');

INSERT INTO flow_skip
  (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, create_time, create_by, update_time, update_by, del_flag)
SELECT 9000000000000000005, d.id, 'SIX_PEOPLE_GROUP', 1, 'PRESIDENT', 1, '通过', 'PASS', NULL, NOW(), 'admin', NOW(), 'admin', '0'
FROM (SELECT id FROM flow_definition WHERE flow_code='rate_approval' AND is_publish=1 LIMIT 1) d
WHERE NOT EXISTS (SELECT 1 FROM flow_skip s WHERE s.definition_id = d.id AND s.now_node_code='SIX_PEOPLE_GROUP' AND s.next_node_code='PRESIDENT');

INSERT INTO flow_skip
  (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, create_time, create_by, update_time, update_by, del_flag)
SELECT 9000000000000000006, d.id, 'PRESIDENT', 1, 'end', 2, '通过', 'PASS', NULL, NOW(), 'admin', NOW(), 'admin', '0'
FROM (SELECT id FROM flow_definition WHERE flow_code='rate_approval' AND is_publish=1 LIMIT 1) d
WHERE NOT EXISTS (SELECT 1 FROM flow_skip s WHERE s.definition_id = d.id AND s.now_node_code='PRESIDENT' AND s.next_node_code='end');

-- ④ 验证:应出现 6 行
SELECT now_node_code, next_node_code, skip_type
FROM flow_skip
WHERE definition_id = (SELECT id FROM flow_definition WHERE flow_code='rate_approval' AND is_publish=1 LIMIT 1)
ORDER BY id;

-- 无规则/矩阵/缓存类配置改动,无需清理 Redis key。

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

-- 20260825_001 需求② 数仓新增「授信担保拆分明细」两表 + 系统分项表加来源列
-- 背景:需求②「存量利率申请与新增一致,按担保方式拆分授信额度,只关注拆分项,不关注合同/不建合同关系」。
--      拆分项数据主体由数仓/核心系统按「授信 × 担保方式」拆好推送,本系统存量申请直接读取展示勾选+填申请利率。
-- 新增表(两张,数仓契约 docs/04 T21/T22):
--   dw_credit_split_snapshot        拆分项主表:客户+授信+担保方式+拆分金额+原利率+到期
--   dw_credit_split_measure_snapshot 拆分项担保措施:每行一个措施,抵押物/保证人字段照抄现有
--                                     dw_mortgage_snapshot / dw_guarantor_snapshot,质押/保证金/存单走通用+ext_json
-- 系统表改动:
--   ccr_pricing_item  加 source_split_no(存量调息来源拆分项,溯源/防重)
-- 幂等:建表用 CREATE TABLE IF NOT EXISTS;加列用 information_schema 判断(PREPARE/EXECUTE),可重复跑。
-- 执行命令:docker exec -i ccrsys-test-mysql-1 mysql -uroot -proot123 --default-character-set=utf8mb4 < db/incr/20260825_001_credit_split_tables.sql

USE ccr_rate;

-- ① T21 拆分项主表
CREATE TABLE IF NOT EXISTS `dw_credit_split_snapshot` (
  `etl_md5`         BIGINT        NOT NULL AUTO_INCREMENT,
  `data_dt`         DATE          NOT NULL COMMENT '数据日期',
  `split_no`        VARCHAR(64)   NOT NULL COMMENT '拆分项编号(唯一业务键,申请勾选/防重)',
  `cust_no`         VARCHAR(64)   NOT NULL COMMENT '客户号',
  `credit_no`       VARCHAR(64)   NULL COMMENT '所属授信编号(顶层授信维度)',
  `contract_no`     VARCHAR(64)   NULL COMMENT '关联贷款合同号(仅参考)',
  `guarantee_type`  VARCHAR(32)   NOT NULL COMMENT '担保方式 MORTGAGE/GUARANTEE/PLEDGE/BILL_MARGIN/CREDIT_MARGIN/CERTIFICATE_DEPOSIT',
  `split_amount`    DECIMAL(18,4) NOT NULL COMMENT '拆分金额(万元)',
  `currency`        VARCHAR(8)    NOT NULL DEFAULT 'CNY' COMMENT '币种',
  `original_rate`   DECIMAL(7,4)  NULL COMMENT '原利率%(预填参考)',
  `maturity_date`   DATE          NULL COMMENT '到期日',
  `split_status`    VARCHAR(16)   NOT NULL COMMENT 'EFFECTIVE有效/SETTLED结清',
  PRIMARY KEY (`etl_md5`),
  KEY `idx_split_no` (`split_no`),
  KEY `idx_split_cust` (`cust_no`)
) ENGINE=InnoDB COMMENT='dw_credit_split_snapshot 授信担保拆分明细(拆分项)';

-- ② T22 拆分项担保措施表(抵押物/保证人字段照抄现有 dw_mortgage_snapshot / dw_guarantor_snapshot)
CREATE TABLE IF NOT EXISTS `dw_credit_split_measure_snapshot` (
  `etl_md5`            BIGINT        NOT NULL AUTO_INCREMENT,
  `data_dt`            DATE          NOT NULL COMMENT '数据日期',
  `split_no`           VARCHAR(64)   NOT NULL COMMENT '所属拆分项',
  `measure_type`       VARCHAR(32)   NOT NULL COMMENT 'MORTGAGE/GUARANTEE/PLEDGE/BILL_MARGIN/CERTIFICATE_DEPOSIT',
  -- 抵押物(对照 dw_mortgage_snapshot)
  `mortgage_type`      VARCHAR(20)   NULL COMMENT '抵押物类型 HOUSE/LAND/EQUIPMENT/VEHICLE/OTHERS',
  `mortgage_name`      VARCHAR(128)  NULL COMMENT '抵押物名称',
  `owner_name`         VARCHAR(128)  NULL COMMENT '权属人名称',
  `owner_cert_no`      VARCHAR(512)  NULL COMMENT '权属人证件号',
  `register_no`        VARCHAR(64)   NULL COMMENT '登记/他项权证编号',
  `assess_value`       DECIMAL(18,4) NULL COMMENT '评估价值(万元)',
  `assess_date`        DATE          NULL COMMENT '评估日期',
  `mortgage_ratio`     DECIMAL(7,4)  NULL COMMENT '抵押率%',
  `mortgage_addr`      VARCHAR(128)  NULL COMMENT '地址',
  -- 保证人(对照 dw_guarantor_snapshot)
  `guarantor_name`     VARCHAR(128)  NULL COMMENT '担保人名称',
  `guarantor_cert_type` VARCHAR(10)  NULL COMMENT '担保人证件类型',
  `guarantor_cert_no`  VARCHAR(512)  NULL COMMENT '担保人证件号',
  `guarantee_type`     VARCHAR(20)   NULL COMMENT '担保方式 GENERAL/JOINT/OTHER',
  `guarantee_amount`   DECIMAL(18,4) NULL COMMENT '担保金额(万元)',
  `guarantee_balance`  DECIMAL(18,4) NULL COMMENT '担保余额(万元)',
  -- 通用(质押/保证金/存单,数仓无现成表)
  `measure_name`       VARCHAR(128)  NULL COMMENT '质押物/存单名称',
  `collateral_no`      VARCHAR(64)   NULL COMMENT '抵质押物/存单编号',
  `currency`           VARCHAR(8)    NOT NULL DEFAULT 'CNY' COMMENT '币种',
  `ext_json`           JSON          NULL COMMENT '类型特有扩展',
  PRIMARY KEY (`etl_md5`),
  KEY `idx_measure_split` (`split_no`)
) ENGINE=InnoDB COMMENT='dw_credit_split_measure_snapshot 拆分项担保措施';

-- ③ 系统分项表加 source_split_no(存量调息来源拆分项)
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'ccr_rate' AND TABLE_NAME = 'ccr_pricing_item' AND COLUMN_NAME = 'source_split_no'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE ccr_pricing_item ADD COLUMN source_split_no VARCHAR(64) NULL COMMENT ''数仓授信担保拆分项编号(存量调息来源,需求②)'' AFTER inherit_flag',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

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

-- ============================================================
-- 增量 003(2026-08-25):他行融资概要可编辑 + 概要/明细对应校验
-- 需求:融资概要(数仓 dw_credit_financing_summary 带出)随申请可编辑持久化;
--   新增/存量客户概要均可在申请页编辑,提交时与融资明细加总做对应校验(§2026-08-25)。
-- 幂等:CREATE TABLE IF NOT EXISTS,可重复执行。
-- ============================================================

USE `ccr_rate`;

CREATE TABLE IF NOT EXISTS `ccr_application_credit_summary` (
  `id`                  BIGINT       NOT NULL COMMENT '雪花主键',
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000' COMMENT '租户标识',
  `business_no`         VARCHAR(64)  NOT NULL COMMENT '表内唯一业务编号',
  `org_id`              BIGINT       NOT NULL COMMENT '数据归属机构',
  `status`              VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '业务状态编码',
  `version_no`          INT          NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
  `create_dept`         BIGINT       NULL COMMENT '创建部门',
  `create_by`           BIGINT       NOT NULL COMMENT '创建人',
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`           BIGINT       NULL COMMENT '最后修改人',
  `update_time`         DATETIME     NULL COMMENT '最后修改时间',
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0' COMMENT '逻辑删除标识(0否 1是)',
  `application_id`      BIGINT       NOT NULL COMMENT '申请主键',
  `lender_count`        INT          NULL COMMENT '授信机构数',
  `credit_amount_total` DECIMAL(20,4) NULL COMMENT '他行授信总额(万元)',
  `used_amount_total`   DECIMAL(20,4) NULL COMMENT '已用额度合计(万元)',
  `loan_account_count`  INT          NULL COMMENT '未结清笔数',
  `overdue_account_count` INT        NULL COMMENT '逾期账户数',
  `overdue_balance`     DECIMAL(20,4) NULL COMMENT '逾期余额(万元)',
  `npl_balance`         DECIMAL(20,4) NULL COMMENT '不良贷款余额(万元)',
  `special_mention_balance` DECIMAL(20,4) NULL COMMENT '关注类余额(万元)',
  `external_guarantee_balance` DECIMAL(20,4) NULL COMMENT '对外担保余额(万元)',
  PRIMARY KEY (`id`),
  KEY `idx_credit_summary_app` (`application_id`)
) ENGINE=InnoDB COMMENT='ccr_application_credit_summary 他行融资概要快照(申请随单可编辑,与明细对应校验,§2026-08-25)';

-- ============================================================
-- 增量 003(2026-08-25):集团五级分类与信用评级
-- 需求:申请页集团信息展示「五级分类」「集团信用评级」(§2026-08-25)。
--   数仓集团主数据 dw_customer_group_snapshot 加 five_level_class/credit_level 两列。
-- 幂等:information_schema 检查列存在,不存在才 ALTER,可重复执行。
-- ============================================================

USE `ccr_rate`;

DROP PROCEDURE IF EXISTS `ccr_add_group_rating`;
DELIMITER $$
CREATE PROCEDURE `ccr_add_group_rating`()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA='ccr_rate' AND TABLE_NAME='dw_customer_group_snapshot'
                   AND COLUMN_NAME='five_level_class') THEN
    ALTER TABLE `dw_customer_group_snapshot`
      ADD COLUMN `five_level_class` VARCHAR(16) NULL COMMENT '五级分类(010正常/020关注/030次级/040可疑/050损失;2026-08-25)',
      ADD COLUMN `credit_level` VARCHAR(32) NULL COMMENT '集团信用评级(如 AAA/AA+;2026-08-25)';
  END IF;
END$$
DELIMITER ;
CALL `ccr_add_group_rating`();
DROP PROCEDURE `ccr_add_group_rating`;

-- ============================================================
-- 增量 004(2026-08-25):产品目录补录协定存款
-- 需求:存款产品应含 5 种(协定/对公定期/通知/银票保证金/信用证保证金)。
--   20260819_001 注释声称存量已有 AGREEMENT_DEPOSIT,实测 ccr_product 目录缺该行
--   (仅 4 种),前端产品下拉按目录加载导致「协定存款」缺失。此处幂等补录。
-- 幂等:INSERT IGNORE 依赖 uk_product_code,可重复执行。
-- ============================================================

USE `ccr_rate`;

INSERT IGNORE INTO `ccr_product`
  (`id`,`tenant_id`,`business_no`,`org_id`,`status`,`version_no`,`create_dept`,`create_by`,`create_time`,`del_flag`,
   `product_code`,`product_name`,`business_big_type`,`product_category`,`customer_type`,`currency`,
   `default_min_rate`,`default_max_rate`,`default_min_term_months`,`default_max_term_months`,`effective_date`)
VALUES
  (2086974517757874183,'000000','2086974517757874183',1000,'ENABLED',1,NULL,1004,NOW(),'0',
   'AGREEMENT_DEPOSIT','协定存款','DEPOSIT','协定','CORPORATE_SINGLE','CNY',0.250000,1.500000,NULL,NULL,'2026-08-01 00:00:00');

-- ============================================================
-- 增量 004(2026-08-25):关联人证件类型列
-- 背景:关联人按证件号反查数仓需区分对公/对私,实体 CcrApplicationRelatedPerson
--   已含 certType 字段(前端 buildPayload/落库/详情带出均引用),本地测试库缺列
--   导致 GET 申请详情 Unknown column 'cert_type'。
-- 幂等:information_schema 检查列存在,可重复执行(仿 24_plaintext_sensitive.sql)。
-- ============================================================

USE `ccr_rate`;

DROP PROCEDURE IF EXISTS `ccr_related_person_cert_type`;
DELIMITER $$
CREATE PROCEDURE `ccr_related_person_cert_type`()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA='ccr_rate' AND TABLE_NAME='ccr_application_related_person'
                   AND COLUMN_NAME='cert_type') THEN
    ALTER TABLE `ccr_application_related_person`
      ADD COLUMN `cert_type` VARCHAR(16) NULL COMMENT '证件类型(USCC对公/ID_CARD对私)' AFTER `cert_no`;
  END IF;
END$$
DELIMITER ;
CALL `ccr_related_person_cert_type`();
DROP PROCEDURE `ccr_related_person_cert_type`;


-- ════════════════════════════════════════════════════════════════
-- 附录:表结构核对修复(2026-08-26,incr 目录无脚本,必须手动执行)
-- ① ccr_pricing_item.calculated_rate(测算利率):全项目在用但无脚本来源,幂等补列
-- ② dw_credit_split_snapshot/dw_credit_split_measure_snapshot 的 etl_md5
--    与 20260821_001 确立的 VARCHAR(100) 口径统一(数仓指纹为字符串;两表为空,先去自增安全)
-- ════════════════════════════════════════════════════════════════
USE `ccr_rate`;
DROP PROCEDURE IF EXISTS `ccr_add_calculated_rate`;
DELIMITER $$
CREATE PROCEDURE `ccr_add_calculated_rate`()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA='ccr_rate' AND TABLE_NAME='ccr_pricing_item'
                   AND COLUMN_NAME='calculated_rate') THEN
    ALTER TABLE `ccr_pricing_item`
      ADD COLUMN `calculated_rate` DECIMAL(9,6) NULL
      COMMENT '测算利率(08-25需求二)' AFTER `current_approval_rate`;
  END IF;
END$$
DELIMITER ;
CALL `ccr_add_calculated_rate`();
DROP PROCEDURE `ccr_add_calculated_rate`;

ALTER TABLE `dw_credit_split_snapshot`
  MODIFY COLUMN `etl_md5` VARCHAR(100) NOT NULL COMMENT 'ETL 校验指纹(数仓推送)';
ALTER TABLE `dw_credit_split_measure_snapshot`
  MODIFY COLUMN `etl_md5` VARCHAR(100) NOT NULL COMMENT 'ETL 校验指纹(数仓推送)';