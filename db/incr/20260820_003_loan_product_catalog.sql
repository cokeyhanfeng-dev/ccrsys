-- 20260820_003 补录贷款产品目录(LOAN_A 对公贷款 / LOAN_P 个人经营性贷款)
-- 背景:生产环境 ccr_product 产品目录仅录入存款 5 种,贷款 2 种(LOAN_A/LOAN_P)缺失,
--      导致申请页「选择产品」下拉(读 /ccr/products/enabled,过滤 status=ENABLED)缺对公/个人经营贷款两项,
--      对公贷款(LOAN_A)提交时选不到产品。测试环境目录齐全(5 存款+2 贷款),生产缺失。
-- 幂等:INSERT IGNORE 依赖 uk_product_code(唯一键 product_code),可重复执行。
-- 影响表:ccr_product(产品目录)。
-- 执行命令:docker exec -i ccr-mysql mysql -uroot -proot123 --default-character-set=utf8mb4 < db/incr/20260820_003_loan_product_catalog.sql

USE ccr_rate;

-- 补录 2 种贷款产品(id/business_no 沿用测试库原值;若生产冲突可改,product_code 唯一键防重复)
INSERT IGNORE INTO `ccr_product`
  (`id`,`tenant_id`,`business_no`,`org_id`,`status`,`version_no`,`create_dept`,`create_by`,`create_time`,`del_flag`,
   `product_code`,`product_name`,`business_big_type`,`product_category`,`customer_type`,`currency`,
   `default_min_rate`,`default_max_rate`,`default_min_term_months`,`default_max_term_months`,`effective_date`,`remark`)
VALUES
  (2086848733646766081,'000000','2086848733789372416',1000,'ENABLED',1,NULL,1004,NOW(),'0',
   'LOAN_A','对公贷款','LOAN','对公贷款','CORPORATE_SINGLE','CNY',2.800000,4.500000,1,60,'2026-08-01 00:00:00','v1 verify'),
  (2086974517757874178,'000000','2086974517829177344',1000,'ENABLED',1,NULL,1004,NOW(),'0',
   'LOAN_P','个人经营性贷款','LOAN','个人经营性贷款','INDIVIDUAL','CNY',3.800000,6.000000,1,36,'2026-08-01 00:00:00','个人经营性贷款');

-- 无规则/矩阵/缓存类配置改动,无需清理 Redis key。
