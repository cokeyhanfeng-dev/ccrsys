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

-- ④ 模拟数据(客户 CUST001:一笔授信按担保方式拆成 抵押500万 + 保证500万,措施明细各挂)
INSERT INTO dw_credit_split_snapshot
  (etl_md5, data_dt, split_no, cust_no, credit_no, contract_no, guarantee_type, split_amount, currency, original_rate, maturity_date, split_status)
VALUES
  (880001, CURDATE(), 'SPLIT-C001-001', 'CUST001', 'CR-C001', 'LC20240001', 'MORTGAGE', 500, 'CNY', 4.35, DATE_ADD(CURDATE(), INTERVAL 1 YEAR), 'EFFECTIVE'),
  (880002, CURDATE(), 'SPLIT-C001-002', 'CUST001', 'CR-C001', 'LC20240001', 'GUARANTEE', 500, 'CNY', 4.65, DATE_ADD(CURDATE(), INTERVAL 1 YEAR), 'EFFECTIVE')
ON DUPLICATE KEY UPDATE split_no = split_no;

INSERT INTO dw_credit_split_measure_snapshot
  (etl_md5, data_dt, split_no, measure_type, mortgage_type, mortgage_name, owner_name, owner_cert_no, register_no,
   assess_value, mortgage_ratio, mortgage_addr, guarantor_name, guarantor_cert_type, guarantor_cert_no,
   guarantee_type, guarantee_amount, guarantee_balance, measure_name, collateral_no, currency, ext_json)
VALUES
  -- 拆分项1:抵押措施×2(住宅+厂房)
  (881001, CURDATE(), 'SPLIT-C001-001', 'MORTGAGE', 'HOUSE', '城东滨湖住宅1栋', '张三', '320282198001010011', '苏(2020)宜兴不动产权第0000111号',
   300, 60, '宜兴市城东街道滨湖路1号', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'CNY', JSON_OBJECT('area', 128)),
  (881002, CURDATE(), 'SPLIT-C001-001', 'MORTGAGE', 'FACTORY', '经开区工业厂房', '宜兴某某实业有限公司', '91320282MA1XXXXXXX', '苏(2021)宜兴不动产权第0000222号',
   400, 50, '宜兴市经开区纬二路8号', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'CNY', JSON_OBJECT('area', 2000)),
  -- 拆分项2:保证措施×1
  (881003, CURDATE(), 'SPLIT-C001-002', 'GUARANTEE', NULL, NULL, NULL, NULL, NULL,
   NULL, NULL, NULL, '李四', 'ID_CARD', '320282198505052233', 'JOINT', 500, 500, NULL, NULL, 'CNY', NULL)
ON DUPLICATE KEY UPDATE split_no = split_no;
