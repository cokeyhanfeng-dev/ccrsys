-- ============================================================
-- 规则引擎种子数据(测试/演示用)
-- 依据:设计文档 V1.0 §8(规则输入/边界/路由)
-- 金额区间约定:含下界,不含上界 [min, max)
-- 用途:验证 RuleEngine 唯一路由、区间连续性、硬边界
-- ============================================================

USE `ccr_rate`;

-- ---------- 规则集 V1 ----------
INSERT INTO `ccr_rate_rule_set`
  (`id`,`tenant_id`,`business_no`,`org_id`,`status`,`version_no`,`create_by`,`create_time`,`del_flag`,
   `set_code`,`set_name`,`effective_from`,`effective_to`,`publish_by`,`review_by`,`publish_time`,`remark`)
VALUES
  (9001,'000000','RULESET20260805001',1001,'EFFECTIVE',1,1000,NOW(),'0',
   'RULE_SET_V1','利率路由规则集V1','2026-08-01 00:00:00',NULL,1000,1000,NOW(),'测试用规则集');

-- ---------- 贷款规则:流动资金贷款 LOAN_A ----------
-- R001 金额<5000 抵押 → 支行行长
INSERT INTO `ccr_rate_rule`
  (`id`,`tenant_id`,`business_no`,`org_id`,`status`,`version_no`,`create_by`,`create_time`,`del_flag`,
   `set_id`,`rule_code`,`rule_name`,`business_type`,`product_code`,`customer_type`,`new_or_existing`,
   `state_owned_flag`,`group_credit_min`,`group_credit_max`,`amount_min`,`amount_max`,
   `term_min`,`term_max`,`term_unit`,`guarantee_type`,`lpr_term`,`org_code`,`currency`,
   `start_node_code`,`rate_direction`,`priority`,`mutex_group`,`description`)
VALUES
  (9101,'000000','RULE20260805001',1001,'ACTIVE',1,1000,NOW(),'0',
   9001,'R001','流动资金<5000抵押→支行','LOAN','LOAN_A',NULL,'EXISTING',NULL,
   NULL,NULL,0,5000,0,12,'MONTH','MORTGAGE',NULL,NULL,'CNY',
   'BRANCH_MANAGER','LOWER_BETTER',1,NULL,'贷款<5000万抵押支行行长终审');

-- R002 金额≥5000 抵押 → 部门总经理
INSERT INTO `ccr_rate_rule`
  (`id`,`tenant_id`,`business_no`,`org_id`,`status`,`version_no`,`create_by`,`create_time`,`del_flag`,
   `set_id`,`rule_code`,`rule_name`,`business_type`,`product_code`,`customer_type`,`new_or_existing`,
   `state_owned_flag`,`group_credit_min`,`group_credit_max`,`amount_min`,`amount_max`,
   `term_min`,`term_max`,`term_unit`,`guarantee_type`,`lpr_term`,`org_code`,`currency`,
   `start_node_code`,`rate_direction`,`priority`,`mutex_group`,`description`)
VALUES
  (9102,'000000','RULE20260805002',1001,'ACTIVE',1,1000,NOW(),'0',
   9001,'R002','流动资金≥5000抵押→部门总经理','LOAN','LOAN_A',NULL,'EXISTING',NULL,
   NULL,NULL,5000,9999999,0,12,'MONTH','MORTGAGE',NULL,NULL,'CNY',
   'DEPT_GENERAL_MANAGER','LOWER_BETTER',1,NULL,'贷款≥5000万抵押部门总经理终审');

-- R003 金额<5000 信用 → 六人小组(超权限上送场景)
INSERT INTO `ccr_rate_rule`
  (`id`,`tenant_id`,`business_no`,`org_id`,`status`,`version_no`,`create_by`,`create_time`,`del_flag`,
   `set_id`,`rule_code`,`rule_name`,`business_type`,`product_code`,`customer_type`,`new_or_existing`,
   `state_owned_flag`,`group_credit_min`,`group_credit_max`,`amount_min`,`amount_max`,
   `term_min`,`term_max`,`term_unit`,`guarantee_type`,`lpr_term`,`org_code`,`currency`,
   `start_node_code`,`rate_direction`,`priority`,`mutex_group`,`description`)
VALUES
  (9103,'000000','RULE20260805003',1001,'ACTIVE',1,1000,NOW(),'0',
   9001,'R003','流动资金<5000信用→六人小组','LOAN','LOAN_A',NULL,'EXISTING',NULL,
   NULL,NULL,0,5000,0,12,'MONTH','CREDIT',NULL,NULL,'CNY',
   'SIX_PEOPLE_GROUP','LOWER_BETTER',1,NULL,'信用方式直接上会表决');

-- ---------- 产品硬边界:LOAN_A 贷款不得低于 3.0% ----------
INSERT INTO `ccr_product_rate_limit`
  (`id`,`tenant_id`,`business_no`,`org_id`,`status`,`version_no`,`create_by`,`create_time`,`del_flag`,
   `product_code`,`product_name`,`business_type`,`hard_boundary_rate`,`rate_direction`,
   `effective_from`,`effective_to`,`publish_by`,`review_by`,`publish_time`)
VALUES
  (9201,'000000','PLR20260805001',1001,'EFFECTIVE',1,1000,NOW(),'0',
   'LOAN_A','流动资金贷款','LOAN',3.000000,'LOWER_BETTER',
   '2026-08-01 00:00:00',NULL,1000,1000,NOW());

-- ---------- 产品硬边界:个人经营性贷款不得低于 3.8%(PRD 表7.2.3;新增不设矩阵绝对下限,走产品硬边界表) ----------
INSERT INTO `ccr_product_rate_limit`
  (`id`,`tenant_id`,`business_no`,`org_id`,`status`,`version_no`,`create_by`,`create_time`,`del_flag`,
   `product_code`,`product_name`,`business_type`,`hard_boundary_rate`,`rate_direction`,
   `effective_from`,`effective_to`,`publish_by`,`review_by`,`publish_time`)
VALUES
  (9202,'000000','PLR20260805002',1001,'EFFECTIVE',1,1000,NOW(),'0',
   'LOAN_P','个人经营性贷款','LOAN',3.800000,'LOWER_BETTER',
   '2026-08-01 00:00:00',NULL,1000,1000,NOW());
