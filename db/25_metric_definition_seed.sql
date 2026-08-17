-- ============================================================
-- 承诺指标字典种子迁移(幂等,只对运行库执行)
-- 内容:ccr_metric_definition 登记 16 项指标(§9 指标字典收敛)
--   权威清单 = 数仓契约 A06(对公8+对私3+派生RATIO) + 集团2 + TOTAL(明细加总展示汇总) + OTHER
--   配置化管理:后台「指标字典」页(admin)增改停用,数仓按字典推送;本文件仅初始化基线
-- 幂等:uk_metric_code 唯一键,INSERT ... ON DUPLICATE KEY UPDATE 重复执行安全
-- 参照:db/05_seed.sql 末尾同内容种子(全新环境),本文件仅运行库补种子
-- ============================================================

USE `ccr_rate`;

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
  (8816,'000000','METRICDICT20260817001',1001,'ACTIVE',1,1004,NOW(),'0','OTHER','其它(手工录入,无数值达成率)','CONTRIBUTION_AMOUNT',NULL,'万元','V1.0')
ON DUPLICATE KEY UPDATE
  `metric_name` = VALUES(`metric_name`),
  `value_type`  = VALUES(`value_type`),
  `metric_scope`= VALUES(`metric_scope`),
  `unit`        = VALUES(`unit`),
  `current_calc_version` = VALUES(`current_calc_version`);
