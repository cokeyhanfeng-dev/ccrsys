-- ============================================================
-- 客户贡献度与利率决策系统 · 基础字典与种子数据
-- 说明:业务字典不依赖 RuoYi 内置 sys_dict,自建 ccr_dict_* 避免耦合;
--      系统级用户/角色/菜单/机构表由 RuoYi-Vue-Plus 框架 init.sql 提供。
-- ============================================================

USE `ccr_rate`;

-- ---------- 业务字典类型 ----------
CREATE TABLE IF NOT EXISTS `ccr_dict_type` (
  `id`          BIGINT      NOT NULL,
  `tenant_id`   VARCHAR(20) NOT NULL DEFAULT '000000',
  `dict_code`   VARCHAR(64) NOT NULL COMMENT '字典类型编码(唯一)',
  `dict_name`   VARCHAR(100) NOT NULL COMMENT '字典类型名称',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME    NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dict_code` (`dict_code`)
) ENGINE=InnoDB COMMENT='ccr_dict_type 业务字典类型';

-- ---------- 业务字典项 ----------
CREATE TABLE IF NOT EXISTS `ccr_dict_item` (
  `id`          BIGINT      NOT NULL,
  `tenant_id`   VARCHAR(20) NOT NULL DEFAULT '000000',
  `dict_code`   VARCHAR(64) NOT NULL COMMENT '字典类型编码',
  `item_code`   VARCHAR(64) NOT NULL COMMENT '字典项编码',
  `item_name`   VARCHAR(200) NOT NULL COMMENT '字典项名称',
  `sort_no`     INT         NOT NULL DEFAULT 1 COMMENT '排序',
  `status`      VARCHAR(8)  NOT NULL DEFAULT 'ENABLE' COMMENT 'ENABLE/DISABLE',
  `ext_json`    JSON        NULL COMMENT '扩展(如边界值、备注)',
  `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME    NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dict_item` (`dict_code`,`item_code`)
) ENGINE=InnoDB COMMENT='ccr_dict_item 业务字典项';

-- ---------- 种子:字典类型 ----------
INSERT INTO `ccr_dict_type` (`id`,`dict_code`,`dict_name`) VALUES
  (1,'ccr_business_type','业务类型'),
  (2,'ccr_customer_scope','客户主体范围'),
  (3,'ccr_carrier_type','定价载体类型'),
  (4,'ccr_guarantee_type','担保主类型'),
  (5,'ccr_rate_direction','利率比较方向'),
  (6,'ccr_application_status','主申请状态'),
  (7,'ccr_pricing_status','定价分项状态'),
  (8,'ccr_vote_round_status','表决批次状态'),
  (9,'ccr_node_code','审批节点'),
  (10,'ccr_term_unit','期限单位'),
  (11,'ccr_metric_scope','指标范围'),
  (12,'ccr_commitment_scope','承诺范围'),
  (13,'ccr_tracking_status','跟踪状态'),
  (14,'ccr_currency','币种'),
  (15,'ccr_record_status','记录状态'),
  (16,'ccr_validation_level','校验等级');

-- ---------- 种子:字典项 ----------
INSERT INTO `ccr_dict_item` (`id`,`dict_code`,`item_code`,`item_name`,`sort_no`) VALUES
  -- 业务类型
  (100,'ccr_business_type','LOAN','贷款',1),
  (101,'ccr_business_type','DEPOSIT','存款',2),
  -- 客户主体范围
  (110,'ccr_customer_scope','INDIVIDUAL','个人',1),
  (111,'ccr_customer_scope','CORPORATE_SINGLE','企业单户',2),
  (112,'ccr_customer_scope','GROUP','集团',3),
  -- 定价载体类型
  (120,'ccr_carrier_type','LOAN_CONTRACT','贷款合同',1),
  (121,'ccr_carrier_type','DEPOSIT_ACCOUNT','存款账户',2),
  -- 担保主类型
  (130,'ccr_guarantee_type','CREDIT','信用',1),
  (131,'ccr_guarantee_type','GUARANTEE','保证',2),
  (132,'ccr_guarantee_type','MORTGAGE','抵押',3),
  (133,'ccr_guarantee_type','PLEDGE','质押',4),
  (134,'ccr_guarantee_type','BILL_MARGIN','银票保证金',5),
  (135,'ccr_guarantee_type','CREDIT_MARGIN','信用保证金',6),
  (136,'ccr_guarantee_type','CERTIFICATE_DEPOSIT','存单质押',7),
  -- 利率比较方向
  (140,'ccr_rate_direction','LOWER_BETTER','越低越优惠(贷款)',1),
  (141,'ccr_rate_direction','HIGHER_BETTER','越高越优惠(存款)',2),
  -- 主申请状态(§12.1)
  (150,'ccr_application_status','DRAFT','草稿',1),
  (151,'ccr_application_status','SUBMITTING','提交冻结中',2),
  (152,'ccr_application_status','PROCESSING','处理中',3),
  (153,'ccr_application_status','PARTIAL_APPROVED','部分通过',4),
  (154,'ccr_application_status','APPROVED','全部通过',5),
  (155,'ccr_application_status','REJECTED','全部否决',6),
  (156,'ccr_application_status','CLOSED','关闭',7),
  -- 定价分项状态(§12.2)
  (160,'ccr_pricing_status','DRAFT','草稿',1),
  (161,'ccr_pricing_status','ROUTING','路由中',2),
  (162,'ccr_pricing_status','LEVEL_APPROVAL','权限审批',3),
  (163,'ccr_pricing_status','VOTING','小组表决',4),
  (164,'ccr_pricing_status','PRESIDENT_DECISION','行长决策',5),
  (165,'ccr_pricing_status','APPROVED','通过',6),
  (166,'ccr_pricing_status','REJECTED','否决',7),
  (167,'ccr_pricing_status','VETOED','一票否决',8),
  (168,'ccr_pricing_status','SUPERSEDED','被替代',9),
  (169,'ccr_pricing_status','CLOSED','关闭',10),
  -- 表决批次状态(§12.3)
  (170,'ccr_vote_round_status','CREATED','已创建',1),
  (171,'ccr_vote_round_status','VOTING','表决中',2),
  (172,'ccr_vote_round_status','COUNTING','计票中',3),
  (173,'ccr_vote_round_status','PASSED','通过',4),
  (174,'ccr_vote_round_status','FAILED','未通过',5),
  (175,'ccr_vote_round_status','CANCELLED','已取消',6),
  -- 审批节点
  (180,'ccr_node_code','BRANCH_MANAGER','支行行长',1),
  (181,'ccr_node_code','DEPT_GENERAL_MANAGER','部门总经理',2),
  (182,'ccr_node_code','VICE_PRESIDENT','分管行长',3),
  (183,'ccr_node_code','SIX_PEOPLE_GROUP','六人小组',4),
  (184,'ccr_node_code','PRESIDENT','总行行长',5),
  -- 期限单位
  (190,'ccr_term_unit','DAY','日',1),
  (191,'ccr_term_unit','MONTH','月',2),
  (192,'ccr_term_unit','YEAR','年',3),
  -- 指标范围
  (200,'ccr_metric_scope','PUBLIC','对公',1),
  (201,'ccr_metric_scope','PRIVATE_SELF','本人对私',2),
  (202,'ccr_metric_scope','RELATED','关联人',3),
  (203,'ccr_metric_scope','GROUP','集团',4),
  (204,'ccr_metric_scope','GROUP_MEMBER','集团成员',5),
  -- 承诺范围(§11.2)
  (210,'ccr_commitment_scope','INDIVIDUAL','个人承诺',1),
  (211,'ccr_commitment_scope','CORPORATE_SINGLE','企业单户承诺',2),
  (212,'ccr_commitment_scope','MEMBER','成员级承诺',3),
  (213,'ccr_commitment_scope','GROUP','集团级承诺',4),
  -- 跟踪状态(§11.1)
  (220,'ccr_tracking_status','PENDING','待开始',1),
  (221,'ccr_tracking_status','TRACKING','跟踪中',2),
  (222,'ccr_tracking_status','AT_RISK','有风险',3),
  (223,'ccr_tracking_status','ACHIEVED','已达成',4),
  (224,'ccr_tracking_status','EXPIRED_UNMET','到期未达成',5),
  (225,'ccr_tracking_status','DATA_PENDING','数据待齐',6),
  (226,'ccr_tracking_status','TERMINATED','已终止',7),
  (227,'ccr_tracking_status','SUPERSEDED','已替代',8),
  -- 币种
  (230,'ccr_currency','CNY','人民币',1),
  (231,'ccr_currency','USD','美元',2),
  (232,'ccr_currency','EUR','欧元',3),
  -- 记录状态
  (240,'ccr_record_status','ACTIVE','有效',1),
  (241,'ccr_record_status','INACTIVE','失效',2),
  (242,'ccr_record_status','DELETED','已删除',3),
  -- 校验等级(§10.5)
  (250,'ccr_validation_level','PASS','通过',1),
  (251,'ccr_validation_level','WARN','预警',2),
  (252,'ccr_validation_level','BLOCK','阻断',3);

-- ---------- 决议执行状态(§12.4) ----------
INSERT INTO `ccr_dict_type` (`id`,`dict_code`,`dict_name`) VALUES
  (17,'ccr_resolution_exec_status','决议执行状态');

INSERT INTO `ccr_dict_item` (`id`,`dict_code`,`item_code`,`item_name`,`sort_no`) VALUES
  (260,'ccr_resolution_exec_status','ISSUED','已签发',1),
  (261,'ccr_resolution_exec_status','CONTRACT_PENDING','待回填合同',2),
  (262,'ccr_resolution_exec_status','CONTRACT_BOUND','已绑定合同',3),
  (263,'ccr_resolution_exec_status','EXECUTED','已执行',4),
  (264,'ccr_resolution_exec_status','RECONCILE_EXCEPTION','核验异常',5),
  (265,'ccr_resolution_exec_status','CLOSED','已关闭',6);

-- ---------- 指标字典(§9;承诺/跟踪指标编号权威来源) ----------
-- metric_code 唯一;value_type: AVG_BALANCE 业务余额/INCOME 收入/CONTRIBUTION_AMOUNT 折算/RATIO 派生
-- 16 项 = 数仓契约 A06(对公8+对私3+派生RATIO) + 集团2 + TOTAL(明细加总展示汇总) + OTHER;数仓按字典推送
-- 配置化管理:后台「指标字典」页(admin)可增改停用;TOTAL 为展示汇总指标,其值 = 其余明细加总
INSERT INTO `ccr_metric_definition`
  (`id`,`tenant_id`,`business_no`,`org_id`,`status`,`version_no`,`create_by`,`create_time`,`del_flag`,
   `metric_code`,`metric_name`,`value_type`,`metric_scope`,`unit`,`current_calc_version`)
VALUES
  (8801,'000000','METRICDICT20260817001',1001,'ACTIVE',1,1004,NOW(),'0','TOTAL','综合贡献总额','CONTRIBUTION_AMOUNT','PUBLIC','万元','V1.0'),
  (8802,'000000','METRICDICT20260817001',1001,'ACTIVE',1,1004,NOW(),'0','GM_LOAN_CONTRIBUTION','贷款贡献','CONTRIBUTION_AMOUNT','GROUP_MEMBER','万元','V1.0'),
  (8803,'000000','METRICDICT20260817001',1001,'ACTIVE',1,1004,NOW(),'0','GM_DEPOSIT_CONTRIBUTION','存款贡献','CONTRIBUTION_AMOUNT','GROUP_MEMBER','万元','V1.0'),
  (8804,'000000','METRICDICT20260817001',1001,'ACTIVE',1,1004,NOW(),'0','PUBLIC_DEPOSIT_AVG','存款日均','AVG_BALANCE','PUBLIC','万元','V1.0'),
  (8805,'000000','METRICDICT20260817001',1001,'ACTIVE',1,1004,NOW(),'0','PUBLIC_PROJECT_LOAN_AVG','贷款日均','AVG_BALANCE','PUBLIC','万元','V1.0'),
  (8806,'000000','METRICDICT20260817001',1001,'ACTIVE',1,1004,NOW(),'0','PUBLIC_DISCOUNT_SPREAD','贴现规模','CONTRIBUTION_AMOUNT','PUBLIC','万元','V1.0'),
  (8807,'000000','METRICDICT20260817001',1001,'ACTIVE',1,1004,NOW(),'0','PUBLIC_OFF_BALANCE_INCOME','对公中间业务收入','INCOME','PUBLIC','万元','V1.0'),
  (8808,'000000','METRICDICT20260817001',1001,'ACTIVE',1,1004,NOW(),'0','PUBLIC_EXCHANGE_SPREAD','结售汇业务总量','CONTRIBUTION_AMOUNT','PUBLIC','万元','V1.0'),
  (8809,'000000','METRICDICT20260817001',1001,'ACTIVE',1,1004,NOW(),'0','PUBLIC_PAYROLL_CONTRIBUTION','代发客户数','CONTRIBUTION_AMOUNT','PUBLIC','户','V1.0'),
  (8810,'000000','METRICDICT20260817001',1001,'ACTIVE',1,1004,NOW(),'0','PUBLIC_PAYROLL_AMOUNT','代发金额','CONTRIBUTION_AMOUNT','PUBLIC','万元','V1.0'),
  (8811,'000000','METRICDICT20260817001',1001,'ACTIVE',1,1004,NOW(),'0','PUBLIC_WEALTH_INCOME','对公财富中收','INCOME','PUBLIC','万元','V1.0'),
  (8812,'000000','METRICDICT20260817001',1001,'ACTIVE',1,1004,NOW(),'0','PRIVATE_DEPOSIT_AVG','对私存款日均','AVG_BALANCE','PRIVATE_SELF','万元','V1.0'),
  (8813,'000000','METRICDICT20260817001',1001,'ACTIVE',1,1004,NOW(),'0','PRIVATE_LOAN_AVG','对私贷款日均','AVG_BALANCE','PRIVATE_SELF','万元','V1.0'),
  (8814,'000000','METRICDICT20260817001',1001,'ACTIVE',1,1004,NOW(),'0','PRIVATE_WEALTH_INCOME','对私财富中收','INCOME','PRIVATE_SELF','万元','V1.0'),
  (8815,'000000','METRICDICT20260817001',1001,'ACTIVE',1,1004,NOW(),'0','PUBLIC_DEPOSIT_LOAN_RATIO','存贷比','RATIO','PUBLIC','%','V1.0'),
  (8816,'000000','METRICDICT20260817001',1001,'ACTIVE',1,1004,NOW(),'0','OTHER','其它(手工录入,无数值达成率)','CONTRIBUTION_AMOUNT',NULL,'万元','V1.0');
