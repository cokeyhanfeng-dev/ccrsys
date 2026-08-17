-- ============================================================
-- 客户贡献度与利率决策系统 · 外部数据落地表(数仓契约)
-- 依据:《ccrd_dw_schema _修订.sql》(v1.1 / PRD V2 D1-D21)
-- 数据由内部数据仓库统一加工落地,本系统消费做快照绑定(D11/D20b)
-- 字符集 utf8mb4;金额统一 DECIMAL(18,4)(万元);敏感字段明文存储(2026-08-17 改)
-- 批次标识:etl_md5 主键 + data_dt 数据日期;业务读取最新 data_dt 成功批次
-- ============================================================

USE `ccr_rate`;

-- ---------- 对公客户主数据(caps_ 数仓现有) ----------
CREATE TABLE IF NOT EXISTS `caps_corp_cust_basic_info` (
  `etl_md5`           CHAR(32)     NOT NULL COMMENT '源系统ETL_MD5',
  `data_dt`           DATE         NOT NULL COMMENT '数据日期',
  `cust_no`           VARCHAR(32)  NOT NULL COMMENT '客户号',
  `cust_name`         VARCHAR(128) NOT NULL COMMENT '客户名称',
  `cert_tp`           VARCHAR(10)  NOT NULL COMMENT '证件类型',
  `cert_no`           VARCHAR(64)  NOT NULL COMMENT '证件号(明文)',
  `ffthlv_class`      VARCHAR(10)  NULL COMMENT '客户五级分类',
  `entp_charic`       VARCHAR(20)  NULL COMMENT '企业性质',
  `entp_scale`        VARCHAR(20)  NULL COMMENT '企业规模',
  `blgd_idsty`        VARCHAR(20)  NULL COMMENT '所属行业',
  `crdt_grd`          VARCHAR(10)  NULL COMMENT '信用等级',
  `entp_empe_num`     INT          NULL COMMENT '企业员工人数',
  `rest_addr`         VARCHAR(256) NULL COMMENT '注册地址',
  `rest_asts`         DECIMAL(18,4) NULL COMMENT '注册资本(万元)',
  `estp_estb_dt`      DATE         NULL COMMENT '企业成立日期',
  `openact_org_no`    VARCHAR(32)  NULL COMMENT '开户机构/基本户所属机构编号',
  `openact_org_nm`    VARCHAR(32)  NULL COMMENT '开户机构/基本户所属机构名称',
  `openact_dt`        DATE         NULL COMMENT '开户日期/基本户开户日期',
  `basic_account_no`  VARCHAR(64)  NULL COMMENT '基本户账户/基本户账号(数仓回填,申请页可改,可空;2026-08-14 新增)',
  `cust_class`        VARCHAR(10)  NOT NULL COMMENT 'EXISTING存客/NEW新客',
  PRIMARY KEY (`etl_md5`),
  KEY `idx_cust_no` (`cust_no`),
  KEY `idx_cert_no` (`cert_no`)
) ENGINE=InnoDB COMMENT='对公客户主数据快照';

-- ---------- 对私客户主数据(caps_ 数仓现有) ----------
CREATE TABLE IF NOT EXISTS `caps_indv_cust_basic_info` (
  `etl_md5`           CHAR(32)     NOT NULL COMMENT '源系统ETL_MD5',
  `data_dt`           DATE         NOT NULL COMMENT '数据日期(DATA_DT)',
  `cust_no`           VARCHAR(32)  NOT NULL COMMENT '客户号(CUST_NO)',
  `cust_nm`           VARCHAR(128) NOT NULL COMMENT '客户姓名(CUST_NM)',
  `cert_tp`           VARCHAR(10)  NOT NULL COMMENT '证件类型(CERT_TP)',
  `cert_no`           VARCHAR(64)  NOT NULL COMMENT '证件号(明文)(CERT_NO)',
  `gnd`               CHAR(64)     NOT NULL COMMENT '性别',
  `ffthlv_class`      VARCHAR(10)  NULL COMMENT '五级分类(FFTHLV_CLASF)',
  `ocupn`             VARCHAR(40)  NULL COMMENT '职业(OCUPN)',
  `whlyr_incm`        DECIMAL(18,4) NULL COMMENT '全年收入(万元)(WHLYR_INCM)',
  `mrrg_sittn`        VARCHAR(10)  NULL COMMENT '婚姻状况(MRRG_SITTN)',
  `rsd_addr`          VARCHAR(256) NULL COMMENT '居住地址(RSD_ADDR)',
  `tel_no`            VARCHAR(32)  NULL COMMENT '联系电话(TEL_NO)',
  `opnact_org_no`     VARCHAR(32)  NULL COMMENT '开户机构/最早开户机构号',
  `opnact_org_nm`     VARCHAR(32)  NULL COMMENT '开户机构/最早开户机构名称',
  `opnact_dt`         DATE         NULL COMMENT '开户日期/最早开户日期',
  `cust_class`        VARCHAR(10)  NOT NULL COMMENT 'EXISTING存客/NEW新客',
  PRIMARY KEY (`etl_md5`),
  KEY `idx_cust_no` (`cust_no`),
  KEY `idx_cert_no` (`cert_no`)
) ENGINE=InnoDB COMMENT='对私客户主数据快照';

-- ---------- 本行融资(并入贷款合同 dw_loan_contract_snapshot,2026-08-11 去冗余) ----------
-- 原 dw_own_financing_snapshot 已删除:原利率=loan_contract.execution_rate、
-- 担保类型=loan_contract.guarantee_type、余额=loan_contract.contract_balance,
-- 申请页存量贷款调息统一从贷款合同取(borrower_customer_no 匹配)

-- ---------- 抵押物快照(T2-抵押,数仓加工,客户经理可补充) ----------
CREATE TABLE IF NOT EXISTS `dw_mortgage_snapshot` (
  `etl_md5`           BIGINT       NOT NULL AUTO_INCREMENT,
  `data_dt`           DATE         NOT NULL COMMENT '数据日期(DATA_DT)',
  `cust_no`           VARCHAR(32)  NOT NULL COMMENT '被担保客户号',
  `contract_no`       VARCHAR(32)  NULL COMMENT '关联贷款合同号(loan_contract.contract_no)',
  `mortgage_type`     VARCHAR(20)  NOT NULL COMMENT '抵押物类型:HOUSE/LAND/EQUIPMENT/VEHICLE/OTHERS',
  `mortgage_name`     VARCHAR(128) NULL COMMENT '抵押物名称',
  `owner_name`        VARCHAR(128) NULL COMMENT '权属人名称',
  `owner_cert_no`     VARCHAR(512) NULL COMMENT '权属人证件号',
  `register_no`       VARCHAR(64)  NULL COMMENT '登记/他项权证编号',
  `assess_value`      DECIMAL(18,4) NULL COMMENT '评估价值(万元)',
  `assess_date`       DATE         NULL COMMENT '评估日期',
  `mortgage_ratio`    DECIMAL(7,4)  NULL COMMENT '抵押率%',
  `mortgage_addr`     VARCHAR(128) NULL COMMENT '抵押物位置',
  `ext_json`          JSON         NULL COMMENT '类型特有元素(厂房/土地:面积/产权证号;土地:使用权类型与到期日;设备:规格型号/数量/购置日期;车辆:车牌/车架号/登记日期)',
  PRIMARY KEY (`etl_md5`),
  KEY `idx_cust` (`cust_no`),
  KEY `idx_contract` (`contract_no`)
) ENGINE=InnoDB COMMENT='抵押物快照(T2-抵押)';

-- ---------- 担保人快照(T2-保证,数仓加工,客户经理可补充) ----------
CREATE TABLE IF NOT EXISTS `dw_guarantor_snapshot` (
  `etl_md5`           BIGINT       NOT NULL AUTO_INCREMENT,
  `data_dt`           DATE         NOT NULL COMMENT '数据日期(DATA_DT)',
  `cust_no`           VARCHAR(32)  NOT NULL COMMENT '被担保客户号',
  `contract_no`       VARCHAR(32)  NULL COMMENT '关联贷款合同号(loan_contract.contract_no)',
  `guarantor_name`    VARCHAR(128) NOT NULL COMMENT '担保人名称',
  `guarantor_cert_type` VARCHAR(10) NULL COMMENT '担保人证件类型',
  `guarantor_cert_no` VARCHAR(512) NULL COMMENT '担保人证件号',
  `guarantee_type`    VARCHAR(20)  NULL COMMENT '担保方式:GENERAL/JOINT/OTHER',
  `guarantee_amount`  DECIMAL(18,4) NULL COMMENT '担保金额(万元)',
  `guarantee_balance` DECIMAL(18,4) NULL COMMENT '担保余额(万元)',
  PRIMARY KEY (`etl_md5`),
  KEY `idx_cust` (`cust_no`),
  KEY `idx_contract` (`contract_no`)
) ENGINE=InnoDB COMMENT='担保人快照(T2-保证)';

-- ---------- 当前贡献度单指标表(T3,D13;TOTAL行+明细行,勾稽在表内) ----------
CREATE TABLE IF NOT EXISTS `dw_contribution_metric` (
  `etl_md5`           BIGINT       NOT NULL AUTO_INCREMENT,
  `data_dt`           DATE         NOT NULL COMMENT '数据日期(DATA_DT)',
  `cust_no`           VARCHAR(32)  NOT NULL,
  `metric_code`       VARCHAR(40)  NOT NULL COMMENT '指标编码;TOTAL=综合贡献总额行,其余为明细(对公9项/对私/关联/集团)',
  `metric_name`       VARCHAR(64)  NOT NULL COMMENT '指标名(TOTAL行为综合贡献总额)',
  `metric_value`      DECIMAL(18,4) NOT NULL COMMENT '指标值(万元);TOTAL行为综合贡献总额',
  `value_type`        VARCHAR(20)  NOT NULL COMMENT 'AVG_BALANCE业务余额/INCOME收入/CONTRIBUTION_AMOUNT折算(D13);TOTAL行为CONTRIBUTION_AMOUNT',
  `metric_scope`      VARCHAR(15)  NOT NULL COMMENT 'PUBLIC/PRIVATE_SELF/RELATED/GROUP集团/GROUP_MEMBER集团成员(单户TOTAL行为PUBLIC,集团TOTAL行为GROUP)',
  `stat_start`        DATE         NULL COMMENT '统计区间起(V1.0 §10.3区间一致性)',
  `stat_end`          DATE         NULL COMMENT '统计区间止(V1.0 §10.3区间一致性)',
  `calc_version`      VARCHAR(32)  NOT NULL DEFAULT 'V1.0' COMMENT '贡献度计算口径版本',
  PRIMARY KEY (`etl_md5`),
  KEY `idx_cust` (`cust_no`),
  KEY `idx_cust_code` (`cust_no`,`metric_code`)
) ENGINE=InnoDB COMMENT='当前贡献度单指标表(T3,D13;TOTAL行+明细行,勾稽在表内;集团TOTAL=成员合计防重复归集)';

-- ---------- 征信报告头(T4,D13d 无时效限制) ----------
CREATE TABLE IF NOT EXISTS `dw_credit_report_snapshot` (
  `etl_md5`           BIGINT       NOT NULL AUTO_INCREMENT,
  `data_dt`           DATE         NOT NULL COMMENT '数据日期(DATA_DT)',
  `cust_no`           VARCHAR(32)  NOT NULL,
  `report_date`       DATE         NULL,
  `parse_status`      VARCHAR(10)  NOT NULL COMMENT 'COMPLETE/PARTIAL',
  PRIMARY KEY (`etl_md5`),
  KEY `idx_cust` (`cust_no`)
) ENGINE=InnoDB COMMENT='征信报告头(T4)';

-- ---------- 征信他行融资明细(T4) ----------
CREATE TABLE IF NOT EXISTS `dw_credit_financing_detail` (
  `etl_md5`           BIGINT       NOT NULL AUTO_INCREMENT,
  `data_dt`           DATE         NOT NULL COMMENT '数据日期(DATA_DT)',
  `report_id`         BIGINT       NOT NULL COMMENT '关联征信头id',
  `customer_no`       VARCHAR(32)  NOT NULL,
  `lender_name`       VARCHAR(64)  NOT NULL COMMENT '他行机构名',
  `credit_amount`     DECIMAL(18,4) NULL COMMENT '授信额(万元)',
  `used_amount`       DECIMAL(18,4) NULL COMMENT '已用额',
  `balance_amount`    DECIMAL(18,4) NULL COMMENT '余额',
  `annual_rate`       DECIMAL(7,4)  NULL COMMENT '年化利率%',
  PRIMARY KEY (`etl_md5`),
  KEY `idx_report` (`report_id`),
  KEY `idx_cust` (`customer_no`)
) ENGINE=InnoDB COMMENT='征信他行融资明细(T4)';

-- ---------- 机构达成情况(T5,D19) ----------
CREATE TABLE IF NOT EXISTS `dw_org_performance_snapshot` (
  `etl_md5`           BIGINT       NOT NULL AUTO_INCREMENT,
  `data_dt`           DATE         NOT NULL COMMENT '数据日期(DATA_DT)',
  `org_code`          VARCHAR(32)  NOT NULL COMMENT '申报机构编码',
  `stat_month`        CHAR(6)      NOT NULL COMMENT '统计月份YYYYMM',
  `achieved_amount`   DECIMAL(18,4) NOT NULL COMMENT '已达成贡献金额(万元)',
  `expected_amount`   DECIMAL(18,4) NOT NULL COMMENT '应达成贡献金额(=已通过申请拟达成合计,D19)',
  `completion_rate`   DECIMAL(7,4)  NOT NULL COMMENT '达成率=已达成/应达成',
  `snapshot_ts`       DATETIME     NOT NULL,
  PRIMARY KEY (`etl_md5`),
  KEY `idx_org_month` (`org_code`,`stat_month`)
) ENGINE=InnoDB COMMENT='机构达成情况快照(T5)';

-- ---------- 他行融资概要(D20) ----------
CREATE TABLE IF NOT EXISTS `dw_credit_financing_summary` (
  `etl_md5`                  BIGINT        NOT NULL AUTO_INCREMENT,
  `data_dt`                  DATE          NOT NULL COMMENT '数据日期(DATA_DT)',
  `report_id`                BIGINT        NOT NULL COMMENT '关联征信头id',
  `cust_no`                  VARCHAR(32)   NOT NULL,
  `lender_count`             INT           NOT NULL COMMENT '授信机构数',
  `npl_balance`              DECIMAL(18,4) NULL COMMENT '不良贷款余额(万元)=次级+可疑+损失类余额',
  `credit_amount_total`      DECIMAL(18,4) NULL COMMENT '他行授信总额(万元)',
  `used_amount_total`        DECIMAL(18,4) NULL COMMENT '他行已用额度合计(万元)',
  `loan_account_count`       INT           NULL COMMENT '未结清贷款笔数',
  `overdue_account_count`    INT           NULL COMMENT '逾期贷款账户数',
  `overdue_balance`          DECIMAL(18,4) NULL COMMENT '逾期贷款余额(万元)',
  `special_mention_balance`  DECIMAL(18,4) NULL COMMENT '关注类贷款余额(万元)',
  `external_guarantee_balance` DECIMAL(18,4) NULL COMMENT '对外担保余额(万元,或有负债)',
  PRIMARY KEY (`etl_md5`),
  KEY `idx_report` (`report_id`),
  KEY `idx_cust` (`cust_no`)
) ENGINE=InnoDB COMMENT='他行融资概要(征信报告)';

-- ============================================================
-- 集团客户数仓数据集(V1.0 §10.2/A.5,集团功能数据源)
-- 层级: 集团-成员-集团授信-成员额度-用信分项-贷款合同-借据
-- ============================================================

-- ---------- 集团客户主数据快照 ----------
CREATE TABLE IF NOT EXISTS `dw_customer_group_snapshot` (
  `etl_md5`           BIGINT       NOT NULL AUTO_INCREMENT,
  `data_dt`           DATE         NOT NULL COMMENT '数据日期(DATA_DT)',
  `group_no`          VARCHAR(64)  NOT NULL COMMENT '集团客户编号',
  `group_name`        VARCHAR(128) NOT NULL COMMENT '集团名称',
  `group_type`        VARCHAR(20)  NOT NULL COMMENT '集团类型',
  `manager_org_id`    VARCHAR(32)  NULL COMMENT '集团主办机构编号',
  `group_status`      VARCHAR(16)  NOT NULL COMMENT '集团状态:NORMAL正常/DISSOLVED解散',
  PRIMARY KEY (`etl_md5`),
  KEY `idx_group_no` (`group_no`)
) ENGINE=InnoDB COMMENT='集团客户主数据快照(V1.0 A.5)';

-- ---------- 集团成员快照 ----------
CREATE TABLE IF NOT EXISTS `dw_customer_group_member_snapshot` (
  `etl_md5`           BIGINT       NOT NULL AUTO_INCREMENT,
  `data_dt`           DATE         NOT NULL COMMENT '数据日期(DATA_DT)',
  `group_no`          VARCHAR(64)  NOT NULL COMMENT '集团客户编号',
  `member_customer_no` VARCHAR(64) NOT NULL COMMENT '成员客户号',
  `member_role`       VARCHAR(20)  NULL COMMENT '成员角色:CORE核心/GENERAL一般',
  `control_relation`  VARCHAR(32)  NULL COMMENT '控制关系:控股/参股等',
  `is_core_member`    CHAR(1)      NOT NULL DEFAULT 'N' COMMENT '是否核心成员Y/N',
  `relation_start`    DATE         NULL COMMENT '入团日期',
  `relation_end`      DATE         NULL COMMENT '退团日期(空=在团)',
  PRIMARY KEY (`etl_md5`),
  KEY `idx_gm_group` (`group_no`),
  KEY `idx_gm_member` (`member_customer_no`)
) ENGINE=InnoDB COMMENT='集团成员快照(V1.0 A.5)';

-- ---------- 客户关系快照(集团外关联关系) ----------
CREATE TABLE IF NOT EXISTS `dw_customer_relation_snapshot` (
  `etl_md5`           BIGINT       NOT NULL AUTO_INCREMENT,
  `data_dt`           DATE         NOT NULL COMMENT '数据日期(DATA_DT)',
  `customer_no`       VARCHAR(64)  NOT NULL COMMENT '客户号',
  `related_customer_no` VARCHAR(64) NOT NULL COMMENT '关联方客户号',
  `relation_type`     VARCHAR(32)  NOT NULL COMMENT '关系类型:GROUP_MEMBER/GUARANTEE/INVEST等',
  `relation_strength` VARCHAR(16)  NULL COMMENT '关系强度:STRONG/WEAK',
  `relation_start`    DATE         NULL COMMENT '关系开始日期',
  `relation_end`      DATE         NULL COMMENT '关系结束日期',
  `relation_status`   VARCHAR(16)  NOT NULL COMMENT 'VALID有效/INVALID失效',
  PRIMARY KEY (`etl_md5`),
  KEY `idx_rel_cust` (`customer_no`),
  KEY `idx_rel_related` (`related_customer_no`)
) ENGINE=InnoDB COMMENT='客户关系快照(V1.0 A.5)';

-- ---------- 集团授信快照 ----------
CREATE TABLE IF NOT EXISTS `dw_group_credit_snapshot` (
  `etl_md5`           BIGINT       NOT NULL AUTO_INCREMENT,
  `data_dt`           DATE         NOT NULL COMMENT '数据日期(DATA_DT)',
  `group_credit_no`   VARCHAR(64)  NOT NULL COMMENT '集团授信编号',
  `group_no`          VARCHAR(64)  NOT NULL COMMENT '集团客户编号',
  `approved_total_amount` DECIMAL(18,4) NOT NULL COMMENT '批复总额度(万元)',
  `allocated_amount`  DECIMAL(18,4) NOT NULL COMMENT '已分配额度(万元)',
  `used_amount`       DECIMAL(18,4) NOT NULL COMMENT '已用额度(万元)',
  `available_amount`  DECIMAL(18,4) NOT NULL COMMENT '可用额度(万元)',
  `currency`          VARCHAR(8)   NOT NULL DEFAULT 'CNY' COMMENT '币种',
  `credit_start`      DATE         NOT NULL COMMENT '授信开始日期',
  `credit_end`        DATE         NOT NULL COMMENT '授信到期日期',
  `revolving_flag`    CHAR(1)      NOT NULL DEFAULT 'N' COMMENT '是否循环额度Y/N',
  `credit_status`     VARCHAR(16)  NOT NULL COMMENT 'EFFECTIVE有效/EXPIRED到期/FROZEN冻结',
  PRIMARY KEY (`etl_md5`),
  KEY `idx_gc_group` (`group_no`),
  KEY `idx_gc_no` (`group_credit_no`)
) ENGINE=InnoDB COMMENT='集团授信快照(V1.0 A.5)';

-- ---------- 成员额度快照 ----------
CREATE TABLE IF NOT EXISTS `dw_member_credit_limit_snapshot` (
  `etl_md5`           BIGINT       NOT NULL AUTO_INCREMENT,
  `data_dt`           DATE         NOT NULL COMMENT '数据日期(DATA_DT)',
  `member_limit_no`   VARCHAR(64)  NOT NULL COMMENT '成员额度编号',
  `group_credit_no`   VARCHAR(64)  NOT NULL COMMENT '所属集团授信编号',
  `member_customer_no` VARCHAR(64) NOT NULL COMMENT '成员客户号',
  `allocation_mode`   VARCHAR(16)  NOT NULL COMMENT 'EXCLUSIVE独占分配/SHARED共享',
  `allocated_amount`  DECIMAL(18,4) NOT NULL COMMENT '分配额度(万元)',
  `used_amount`       DECIMAL(18,4) NOT NULL COMMENT '已用额度(万元)',
  `available_amount`  DECIMAL(18,4) NOT NULL COMMENT '可用额度(万元)',
  `shared_limit_group_no` VARCHAR(64) NULL COMMENT '共享额度组编号(SHARED场景)',
  `currency`          VARCHAR(8)   NOT NULL DEFAULT 'CNY' COMMENT '币种',
  `limit_start`       DATE         NOT NULL COMMENT '额度开始日期',
  `limit_end`         DATE         NOT NULL COMMENT '额度到期日期',
  `limit_status`      VARCHAR(16)  NOT NULL COMMENT 'EFFECTIVE有效/EXPIRED到期/FROZEN冻结',
  PRIMARY KEY (`etl_md5`),
  KEY `idx_ml_no` (`member_limit_no`),
  KEY `idx_ml_gc` (`group_credit_no`),
  KEY `idx_ml_member` (`member_customer_no`)
) ENGINE=InnoDB COMMENT='成员额度快照(V1.0 A.5)';

-- ---------- 用信分项快照 ----------
CREATE TABLE IF NOT EXISTS `dw_credit_tranche_snapshot` (
  `etl_md5`           BIGINT       NOT NULL AUTO_INCREMENT,
  `data_dt`           DATE         NOT NULL COMMENT '数据日期(DATA_DT)',
  `tranche_no`        VARCHAR(64)  NOT NULL COMMENT '用信分项编号',
  `member_limit_no`   VARCHAR(64)  NOT NULL COMMENT '所属成员额度编号',
  `member_customer_no` VARCHAR(64) NOT NULL COMMENT '成员客户号',
  `product_code`      VARCHAR(32)  NOT NULL COMMENT '产品编码',
  `main_guarantee_type` VARCHAR(32) NOT NULL COMMENT '担保主类型',
  `tranche_amount`    DECIMAL(18,4) NOT NULL COMMENT '分项金额(万元)',
  `used_amount`       DECIMAL(18,4) NOT NULL COMMENT '已用金额(万元)',
  `available_amount`  DECIMAL(18,4) NOT NULL COMMENT '可用金额(万元)',
  `currency`          VARCHAR(8)   NOT NULL DEFAULT 'CNY' COMMENT '币种',
  `term_start`        DATE         NOT NULL COMMENT '分项开始日期',
  `term_end`          DATE         NOT NULL COMMENT '分项到期日期',
  `tranche_status`    VARCHAR(16)  NOT NULL COMMENT 'EFFECTIVE有效/EXPIRED到期/CLOSED结清',
  PRIMARY KEY (`etl_md5`),
  KEY `idx_tr_no` (`tranche_no`),
  KEY `idx_tr_ml` (`member_limit_no`),
  KEY `idx_tr_member` (`member_customer_no`)
) ENGINE=InnoDB COMMENT='用信分项快照(V1.0 A.5)';

-- ---------- 贷款合同快照 ----------
CREATE TABLE IF NOT EXISTS `dw_loan_contract_snapshot` (
  `etl_md5`           BIGINT       NOT NULL AUTO_INCREMENT,
  `data_dt`           DATE         NOT NULL COMMENT '数据日期(DATA_DT)',
  `contract_no`       VARCHAR(64)  NOT NULL COMMENT '贷款合同号',
  `agreement_no`      VARCHAR(64)  NULL COMMENT '所属授信协议编号(关联 dw_credit_agreement_snapshot.agreement_no)',
  `tranche_no`        VARCHAR(64)  NULL COMMENT '所属用信分项编号',
  `borrower_customer_no` VARCHAR(64) NOT NULL COMMENT '借款人客户号',
  `contract_amount`   DECIMAL(18,4) NOT NULL COMMENT '合同金额(万元)',
  `contract_balance`  DECIMAL(18,4) NOT NULL COMMENT '合同余额(万元)',
  `guarantee_type`    VARCHAR(16)  NULL COMMENT '担保主类型(原 dw_own_financing 迁移:MORTGAGE/CREDIT/GUARANTEE 等)',
  `currency`          VARCHAR(8)   NOT NULL DEFAULT 'CNY' COMMENT '币种',
  `execution_rate`    DECIMAL(7,4)  NOT NULL COMMENT '合同执行利率%(存量调息原利率来源)',
  `rate_type`         VARCHAR(16)  NOT NULL COMMENT 'FIXED固定/LPR_PLUS LPR加点',
  `lpr_term`          VARCHAR(8)   NULL COMMENT 'LPR期限:1Y/5Y(LPR加点时必填)',
  `start_date`        DATE         NOT NULL COMMENT '合同起始日',
  `maturity_date`     DATE         NOT NULL COMMENT '合同到期日',
  `contract_status`   VARCHAR(16)  NOT NULL COMMENT 'EFFECTIVE有效/SETTLED结清/OVERDUE逾期',
  `contract_version`  INT          NOT NULL DEFAULT 1 COMMENT '合同版本(补充协议递增)',
  PRIMARY KEY (`etl_md5`),
  KEY `idx_lc_no` (`contract_no`),
  KEY `idx_lc_agreement` (`agreement_no`),
  KEY `idx_lc_tranche` (`tranche_no`),
  KEY `idx_lc_borrower` (`borrower_customer_no`)
) ENGINE=InnoDB COMMENT='贷款合同快照(V1.0 A.5);agreement_no 关联授信协议;guarantee_type 承原本行融资担保';

-- ---------- 借据快照 ----------
CREATE TABLE IF NOT EXISTS `dw_loan_note_snapshot` (
  `etl_md5`           BIGINT       NOT NULL AUTO_INCREMENT,
  `data_dt`           DATE         NOT NULL COMMENT '数据日期(DATA_DT)',
  `loan_note_no`      VARCHAR(64)  NOT NULL COMMENT '借据号',
  `agreement_no`      VARCHAR(64)  NULL COMMENT '所属授信协议编号(关联 dw_credit_agreement_snapshot.agreement_no)',
  `contract_no`       VARCHAR(64)  NOT NULL COMMENT '所属贷款合同号',
  `tranche_no`        VARCHAR(64)  NULL COMMENT '所属用信分项编号',
  `borrower_customer_no` VARCHAR(64) NOT NULL COMMENT '借款人客户号',
  `loan_amount`       DECIMAL(18,4) NOT NULL COMMENT '借据金额(万元)',
  `loan_balance`      DECIMAL(18,4) NOT NULL COMMENT '借据余额(万元)',
  `currency`          VARCHAR(8)   NOT NULL DEFAULT 'CNY' COMMENT '币种',
  `execution_rate`    DECIMAL(7,4)  NOT NULL COMMENT '借据执行利率%',
  `rate_type`         VARCHAR(16)  NOT NULL COMMENT 'FIXED固定/LPR_PLUS LPR加点',
  `lpr_term`          VARCHAR(8)   NULL COMMENT 'LPR期限:1Y/5Y',
  `start_date`        DATE         NOT NULL COMMENT '放款日期',
  `maturity_date`     DATE         NOT NULL COMMENT '借据到期日',
  `note_status`       VARCHAR(16)  NOT NULL COMMENT 'NORMAL正常/SETTLED结清/OVERDUE逾期',
  PRIMARY KEY (`etl_md5`),
  KEY `idx_ln_no` (`loan_note_no`),
  KEY `idx_ln_agreement` (`agreement_no`),
  KEY `idx_ln_contract` (`contract_no`),
  KEY `idx_ln_borrower` (`borrower_customer_no`)
) ENGINE=InnoDB COMMENT='借据快照(V1.0 A.5;决议核验比对合同利率);agreement_no 关联授信协议';

-- ---------- 存款账户快照 ----------
CREATE TABLE IF NOT EXISTS `dw_deposit_account_snapshot` (
  `etl_md5`           BIGINT       NOT NULL AUTO_INCREMENT,
  `data_dt`           DATE         NOT NULL COMMENT '数据日期(DATA_DT)',
  `deposit_account_no` VARCHAR(64)  NOT NULL COMMENT '存款账号(明文)',
  `customer_no`       VARCHAR(64)  NOT NULL COMMENT '客户号',
  `product_code`      VARCHAR(32)  NOT NULL COMMENT '存款产品编码',
  `account_balance`   DECIMAL(18,4) NOT NULL COMMENT '账户余额(万元)',
  `currency`          VARCHAR(8)   NOT NULL DEFAULT 'CNY' COMMENT '币种',
  `execution_rate`    DECIMAL(7,4)  NOT NULL COMMENT '执行利率%',
  `rate_type`         VARCHAR(16)  NOT NULL COMMENT 'FIXED固定/FLOAT浮动',
  `term_value`        INT          NULL COMMENT '期限数值(活期为空)',
  `term_unit`         VARCHAR(8)   NULL COMMENT '期限单位:日/月/年',
  `open_date`         DATE         NOT NULL COMMENT '开户日期',
  `maturity_date`     DATE         NULL COMMENT '到期日期(活期为空)',
  `account_status`    VARCHAR(16)  NOT NULL COMMENT 'NORMAL正常/CLOSED销户/FROZEN冻结',
  PRIMARY KEY (`etl_md5`),
  KEY `idx_da_no` (`deposit_account_no`),
  KEY `idx_da_cust` (`customer_no`)
) ENGINE=InnoDB COMMENT='存款账户快照(V1.0 A.5)';

-- ============================================================
-- 说明:关联人无独立关系表;关联人身份由客户经理申请时录入(证件号),
-- 数仓按其证件号反查关联人贡献度明细,承载在 dw_contribution_metric.metric_scope='RELATED' 行(PRD V2 §8.2)
-- ============================================================

-- ---------- 授信协议快照(手工测试/联调补充;数仓契约待正式推送确认) ----------
-- 授信信息展示口径(2026-08 业务确认):授信协议编号/授信类型/开始日期/结束日期/授信额度/已用额度
CREATE TABLE IF NOT EXISTS `dw_credit_agreement_snapshot` (
  `etl_md5`           BIGINT       NOT NULL AUTO_INCREMENT,
  `data_dt`           DATE         NOT NULL COMMENT '数据日期(DATA_DT)',
  `agreement_no`      VARCHAR(64)  NOT NULL COMMENT '授信协议编号',
  `customer_no`       VARCHAR(64)  NOT NULL COMMENT '客户号(单户或集团成员)',
  `agreement_type`    VARCHAR(32)  NOT NULL COMMENT '授信类型:COMPREHENSIVE综合授信/SINGLE单笔单批/REVOLVING循环授信',
  `credit_amount`     DECIMAL(18,4) NOT NULL COMMENT '授信额度(万元)',
  `used_amount`       DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '已用额度(万元)',
  `available_amount`  DECIMAL(18,4) NOT NULL COMMENT '可用额度(万元)',
  `currency`          VARCHAR(8)   NOT NULL DEFAULT 'CNY',
  `start_date`        DATE         NOT NULL COMMENT '开始日期',
  `end_date`          DATE         NOT NULL COMMENT '结束日期',
  `agreement_status`  VARCHAR(16)  NOT NULL COMMENT 'EFFECTIVE有效/EXPIRED到期/CLOSED终止',
  PRIMARY KEY (`etl_md5`),
  KEY `idx_agr_no` (`agreement_no`),
  KEY `idx_agr_cust` (`customer_no`)
) ENGINE=InnoDB COMMENT='授信协议快照(数仓推送契约补充)';
