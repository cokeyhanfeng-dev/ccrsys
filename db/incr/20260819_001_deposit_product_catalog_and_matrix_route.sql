-- 20260819_001 存款产品目录补全+矩阵路由互斥修复
-- 需求:存款产品应含 5 种(协定/对公定期/通知/银票保证金/信用证保证金),原产品目录仅协定 1 种;
--      矩阵对公定期 5 行产品码通配 + 保证金 2 行业务大类 MARGIN 不可达 → 提交存款报「权限矩阵多匹配:优先级1命中2行」。
-- 幂等:INSERT IGNORE 依赖 uk_product_code;UPDATE 按 matrix_no 定位可重复执行(结果收敛)。

USE ccr_rate;

-- ① 补录 4 种存款产品(与存量 AGREEMENT_DEPOSIT 协定存款合计 5 种齐全;业务大类 DEPOSIT,与矩阵匹配口径一致)
INSERT IGNORE INTO `ccr_product`
  (`id`,`tenant_id`,`business_no`,`org_id`,`status`,`version_no`,`create_dept`,`create_by`,`create_time`,`del_flag`,
   `product_code`,`product_name`,`business_big_type`,`product_category`,`customer_type`,`currency`,
   `default_min_rate`,`default_max_rate`,`default_min_term_months`,`default_max_term_months`,`effective_date`)
VALUES
  (2086974517757874179,'000000','2086974517757874179',1000,'ENABLED',1,NULL,1004,NOW(),'0',
   'CORP_TIME_DEPOSIT','对公定期存款','DEPOSIT','对公定期','CORPORATE_SINGLE','CNY',0.100000,1.580000,1,60,'2026-08-01 00:00:00'),
  (2086974517757874180,'000000','2086974517757874180',1000,'ENABLED',1,NULL,1004,NOW(),'0',
   'NOTICE_DEPOSIT','通知存款','DEPOSIT','通知','CORPORATE_SINGLE','CNY',0.100000,0.500000,1,12,'2026-08-01 00:00:00'),
  (2086974517757874181,'000000','2086974517757874181',1000,'ENABLED',1,NULL,1004,NOW(),'0',
   'BILL_MARGIN','银票保证金','DEPOSIT','银票保证金','CORPORATE_SINGLE','CNY',0.100000,1.500000,1,12,'2026-08-01 00:00:00'),
  (2086974517757874182,'000000','2086974517757874182',1000,'ENABLED',1,NULL,1004,NOW(),'0',
   'CREDIT_MARGIN','信用证保证金','DEPOSIT','信用证保证金','CORPORATE_SINGLE','CNY',0.100000,1.500000,1,12,'2026-08-01 00:00:00');

-- ② 矩阵互斥修复:对公定期 5 行补产品码
--    (原 product_code=NULL 通配,与协定/通知/保证金同优先级多行互撞 → RULE_MULTI_MATCH;补码后互斥)
UPDATE `ccr_rate_matrix` SET `product_code`='CORP_TIME_DEPOSIT'
 WHERE `matrix_no` IN ('M-DEP-TIME-3M','M-DEP-TIME-6M','M-DEP-TIME-1Y','M-DEP-TIME-2Y','M-DEP-TIME-3Y');

-- ③ 矩阵互斥修复:银票/信用证保证金 2 行业务大类 MARGIN→DEPOSIT
--    (业务大类映射 ApplicationSubmitServiceImpl.businessBigType 对存款恒返 DEPOSIT,MARGIN 不可达;
--     原通配产品码与定期行互撞 → 多匹配)
UPDATE `ccr_rate_matrix` SET `business_big_type`='DEPOSIT'
 WHERE `matrix_no` IN ('M-MARGIN-BILL','M-MARGIN-CREDIT');

-- 部署后清理缓存(矩阵/产品路由读缓存):
-- DEL ccr:cfg:matrix:effective
