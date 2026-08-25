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
