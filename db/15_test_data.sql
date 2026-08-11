-- ============================================================
-- 手工测试数据集(配合 docs/06_测试方案.md / docs/07_手工测试用例.md 使用)
-- 内容:单户存量贷款合同/借据、贡献度(勾稽一致)、关联人、征信、机构达成、存款产品硬边界
-- 幂等:全部 ON DUPLICATE KEY / 可重复执行;data_dt 用 CURDATE() 保证不过期(3天时效阻断)
-- 执行:docker exec -i ccr-mysql mysql -uroot -proot123 --default-character-set=utf8mb4 ccr_rate < db/15_test_data.sql
-- ============================================================

USE `ccr_rate`;

-- ---------- 0. 刷新既有 mock 数据日期(防 3 天时效阻断;新库随 initdb 自动生效) ----------
-- 注:dw_own_financing_snapshot 已于 2026-08-11 去冗余删除(并入 dw_loan_contract_snapshot)
UPDATE dw_contribution_metric SET data_dt=CURDATE();
UPDATE dw_mortgage_snapshot SET data_dt=CURDATE();
UPDATE dw_guarantor_snapshot SET data_dt=CURDATE();
UPDATE dw_credit_report_snapshot SET data_dt=CURDATE();
UPDATE dw_credit_financing_summary SET data_dt=CURDATE();
UPDATE dw_credit_financing_detail SET data_dt=CURDATE();
UPDATE dw_org_performance_snapshot SET data_dt=CURDATE();
UPDATE caps_corp_cust_basic_info SET data_dt=CURDATE();
UPDATE caps_indv_cust_basic_info SET data_dt=CURDATE();
UPDATE dw_customer_group_snapshot SET data_dt=CURDATE();
UPDATE dw_customer_group_member_snapshot SET data_dt=CURDATE();
UPDATE dw_customer_relation_snapshot SET data_dt=CURDATE();
UPDATE dw_group_credit_snapshot SET data_dt=CURDATE();
UPDATE dw_member_credit_limit_snapshot SET data_dt=CURDATE();
UPDATE dw_credit_tranche_snapshot SET data_dt=CURDATE();
UPDATE dw_loan_contract_snapshot SET data_dt=CURDATE();
UPDATE dw_loan_note_snapshot SET data_dt=CURDATE();
UPDATE dw_deposit_account_snapshot SET data_dt=CURDATE();

-- ---------- 1. 机构达成(dw_org_performance,机构编码对齐 ccr_sys_dept.dept_code) ----------
-- 审批详情"机构达成"卡按申请机构 dept_code 取数;10_mock 的 '1001' 为旧编码,此处修正
DELETE FROM dw_org_performance_snapshot WHERE org_code='1001';
INSERT INTO dw_org_performance_snapshot (etl_md5,data_dt,org_code,stat_month,achieved_amount,expected_amount,completion_rate,snapshot_ts) VALUES
(3001,CURDATE(),'CDZH','202607',1828.0000,2000.0000,0.9140,NOW()),
(3002,CURDATE(),'CXZH','202607',1400.0000,1600.0000,0.8750,NOW()),
(3003,CURDATE(),'YCZH','202607',608.0000,800.0000,0.7600,NOW())
ON DUPLICATE KEY UPDATE completion_rate=VALUES(completion_rate);

-- ---------- 2. 单户存量贷款合同(存量调息测试,T1-08/09/10 等) ----------
-- CUST001(非国企) 原利率 4.00%;CUST002(国企) 4.20%;CUST101(个人) 4.50%
-- agreement_no 关联授信协议(AGR-C001-2026/AGR-C002-2026/AGR-C101-2026)
INSERT INTO dw_loan_contract_snapshot (etl_md5,data_dt,contract_no,agreement_no,tranche_no,borrower_customer_no,contract_amount,contract_balance,guarantee_type,currency,execution_rate,rate_type,lpr_term,start_date,maturity_date,contract_status,contract_version) VALUES
(5001,CURDATE(),'LC20260001','AGR-C001-2026',NULL,'CUST001',5000.0000,4800.0000,'MORTGAGE','CNY',4.0000,'LPR_PLUS','1Y','2026-01-15','2027-01-15','EFFECTIVE',1),
(5002,CURDATE(),'LC20260002','AGR-C002-2026',NULL,'CUST002',3000.0000,3000.0000,'MORTGAGE','CNY',4.2000,'LPR_PLUS','1Y','2026-02-10','2027-02-10','EFFECTIVE',1),
(5003,CURDATE(),'LC20260003','AGR-C101-2026',NULL,'CUST101',500.0000,480.0000,'GUARANTEE','CNY',4.5000,'LPR_PLUS','1Y','2026-03-01','2027-03-01','EFFECTIVE',1)
ON DUPLICATE KEY UPDATE contract_balance=VALUES(contract_balance), guarantee_type=VALUES(guarantee_type);

-- 借据:CUST001 两笔一致(正常路径);CUST002 的 NB_C202 利率 4.35≠合同 4.20(核验异常演示)
INSERT INTO dw_loan_note_snapshot (etl_md5,data_dt,loan_note_no,agreement_no,contract_no,tranche_no,borrower_customer_no,loan_amount,loan_balance,currency,execution_rate,rate_type,lpr_term,start_date,maturity_date,note_status) VALUES
(5011,CURDATE(),'NB_C101','AGR-C001-2026','LC20260001',NULL,'CUST001',3000.0000,3000.0000,'CNY',4.0000,'LPR_PLUS','1Y','2026-01-20','2027-01-15','NORMAL'),
(5012,CURDATE(),'NB_C102','AGR-C001-2026','LC20260001',NULL,'CUST001',2000.0000,1800.0000,'CNY',4.0000,'LPR_PLUS','1Y','2026-02-01','2027-01-15','NORMAL'),
(5013,CURDATE(),'NB_C201','AGR-C002-2026','LC20260002',NULL,'CUST002',2000.0000,2000.0000,'CNY',4.2000,'LPR_PLUS','1Y','2026-02-15','2027-02-10','NORMAL'),
(5014,CURDATE(),'NB_C202','AGR-C002-2026','LC20260002',NULL,'CUST002',1000.0000,1000.0000,'CNY',4.3500,'LPR_PLUS','1Y','2026-03-01','2027-02-10','NORMAL'),
(5015,CURDATE(),'NB_C301','AGR-C101-2026','LC20260003',NULL,'CUST101',500.0000,480.0000,'CNY',4.5000,'LPR_PLUS','1Y','2026-03-05','2027-03-01','NORMAL')
ON DUPLICATE KEY UPDATE loan_balance=VALUES(loan_balance);

-- ---------- 3. 贡献度(勾稽口径:TOTAL=同客户同 scope 明细加总) ----------
-- CUST002(国企)对公 8 项:10+20+5+5+8+2+3+7=60
INSERT INTO dw_contribution_metric (etl_md5,data_dt,cust_no,metric_code,metric_name,metric_value,value_type,metric_scope,stat_start,stat_end,calc_version) VALUES
(5101,CURDATE(),'CUST002','TOTAL','综合贡献总额',60.0000,'CONTRIBUTION_AMOUNT','PUBLIC','2026-07-01','2026-07-31','V1.0'),
(5102,CURDATE(),'CUST002','PUBLIC_DEPOSIT_AVG','存款日均',10.0000,'CONTRIBUTION_AMOUNT','PUBLIC','2026-07-01','2026-07-31','V1.0'),
(5103,CURDATE(),'CUST002','PUBLIC_LOAN_AVG','贷款日均',20.0000,'CONTRIBUTION_AMOUNT','PUBLIC','2026-07-01','2026-07-31','V1.0'),
(5104,CURDATE(),'CUST002','PUBLIC_PROJECT_LOAN_AVG','项目贷日均',5.0000,'CONTRIBUTION_AMOUNT','PUBLIC','2026-07-01','2026-07-31','V1.0'),
(5105,CURDATE(),'CUST002','PUBLIC_DISCOUNT','贴现利差收益',5.0000,'CONTRIBUTION_AMOUNT','PUBLIC','2026-07-01','2026-07-31','V1.0'),
(5106,CURDATE(),'CUST002','PUBLIC_INTERMEDIATE','对公中间业务收入',8.0000,'CONTRIBUTION_AMOUNT','PUBLIC','2026-07-01','2026-07-31','V1.0'),
(5107,CURDATE(),'CUST002','PUBLIC_EXCHANGE','汇兑利差收益',2.0000,'CONTRIBUTION_AMOUNT','PUBLIC','2026-07-01','2026-07-31','V1.0'),
(5108,CURDATE(),'CUST002','PUBLIC_PAYROLL','代发贡献度',3.0000,'CONTRIBUTION_AMOUNT','PUBLIC','2026-07-01','2026-07-31','V1.0'),
(5109,CURDATE(),'CUST002','PUBLIC_WEALTH','对公财富中收',7.0000,'CONTRIBUTION_AMOUNT','PUBLIC','2026-07-01','2026-07-31','V1.0')
ON DUPLICATE KEY UPDATE metric_value=VALUES(metric_value);

-- CUST101(个人):本人存/贷/财富 3 项:15+25+10=50
INSERT INTO dw_contribution_metric (etl_md5,data_dt,cust_no,metric_code,metric_name,metric_value,value_type,metric_scope,stat_start,stat_end,calc_version) VALUES
(5111,CURDATE(),'CUST101','TOTAL','综合贡献总额',50.0000,'CONTRIBUTION_AMOUNT','PRIVATE_SELF','2026-07-01','2026-07-31','V1.0'),
(5112,CURDATE(),'CUST101','PRIVATE_DEPOSIT_AVG','本人存款日均',15.0000,'CONTRIBUTION_AMOUNT','PRIVATE_SELF','2026-07-01','2026-07-31','V1.0'),
(5113,CURDATE(),'CUST101','PRIVATE_LOAN_AVG','本人贷款日均',25.0000,'CONTRIBUTION_AMOUNT','PRIVATE_SELF','2026-07-01','2026-07-31','V1.0'),
(5114,CURDATE(),'CUST101','PRIVATE_WEALTH','本人财富中收',10.0000,'CONTRIBUTION_AMOUNT','PRIVATE_SELF','2026-07-01','2026-07-31','V1.0')
ON DUPLICATE KEY UPDATE metric_value=VALUES(metric_value);

-- CUST001 本人(对私口径)与关联人贡献(双概念/分层展示;PRIVATE_SELF 口径 TOTAL=12+8=20)
INSERT INTO dw_contribution_metric (etl_md5,data_dt,cust_no,metric_code,metric_name,metric_value,value_type,metric_scope,stat_start,stat_end,calc_version) VALUES
(5120,CURDATE(),'CUST001','TOTAL','本人综合贡献',20.0000,'CONTRIBUTION_AMOUNT','PRIVATE_SELF','2026-07-01','2026-07-31','V1.0'),
(5121,CURDATE(),'CUST001','SELF_DEPOSIT_AVG','本人存款日均',12.0000,'CONTRIBUTION_AMOUNT','PRIVATE_SELF','2026-07-01','2026-07-31','V1.0'),
(5122,CURDATE(),'CUST001','SELF_LOAN_AVG','本人贷款日均',8.0000,'CONTRIBUTION_AMOUNT','PRIVATE_SELF','2026-07-01','2026-07-31','V1.0'),
(5123,CURDATE(),'REL001','TOTAL','关联人综合贡献',30.0000,'CONTRIBUTION_AMOUNT','RELATED','2026-07-01','2026-07-31','V1.0'),
(5124,CURDATE(),'REL001','REL_DEPOSIT_AVG','关联人存款日均',18.0000,'CONTRIBUTION_AMOUNT','RELATED','2026-07-01','2026-07-31','V1.0'),
(5125,CURDATE(),'REL001','REL_WEALTH','关联人财富中收',12.0000,'CONTRIBUTION_AMOUNT','RELATED','2026-07-01','2026-07-31','V1.0')
ON DUPLICATE KEY UPDATE metric_value=VALUES(metric_value);

-- ---------- 4. 关联人(主数据+关系) ----------
INSERT INTO caps_corp_cust_basic_info (etl_md5,data_dt,cust_no,cust_name,cert_tp,cert_no,ffthlv_class,entp_charic,entp_scale,blgd_idsty,crdt_grd,entp_empe_num,rest_addr,rest_asts,estp_estb_dt,openact_org_no,openact_org_nm,openact_dt,cust_class) VALUES
('md5_corp_rel1',CURDATE(),'REL001','江苏某某贸易有限公司','UNIFIED','91320000XXXXXXXXX4','正常','NON_SOE','小型','批发零售','A-',120,'南京市XX路8号',800.0000,'2019-06-01',1001,'城东支行','2020-01-10','EXISTING')
ON DUPLICATE KEY UPDATE cust_name=VALUES(cust_name);

INSERT INTO dw_customer_relation_snapshot (etl_md5,data_dt,customer_no,related_customer_no,relation_type,relation_strength,relation_start,relation_end,relation_status) VALUES
(5131,CURDATE(),'CUST001','REL001','SAME_CONTROLLER','STRONG','2020-01-01',NULL,'VALID')
ON DUPLICATE KEY UPDATE relation_status=VALUES(relation_status);

-- ---------- 5. 征信报告(parse_status=COMPLETE;质量校验用) ----------
INSERT INTO dw_credit_report_snapshot (etl_md5,data_dt,cust_no,report_date,parse_status) VALUES
(5201,CURDATE(),'CUST001',CURDATE(),'COMPLETE'),
(5202,CURDATE(),'CUST002',CURDATE(),'COMPLETE'),
(5203,CURDATE(),'CUST101',CURDATE(),'COMPLETE')
ON DUPLICATE KEY UPDATE parse_status=VALUES(parse_status);

-- ---------- 6. 存款/保证金产品硬边界(全行上限,HIGHER_BETTER;供硬边界校验与前端上限展示) ----------
INSERT INTO `ccr_product_rate_limit`
  (`id`,`tenant_id`,`business_no`,`org_id`,`status`,`version_no`,`create_by`,`create_time`,`del_flag`,
   `product_code`,`product_name`,`business_type`,`hard_boundary_rate`,`rate_direction`,
   `effective_from`,`effective_to`,`publish_by`,`review_by`,`publish_time`)
VALUES
  (9211,'000000','PLR20260806011',1001,'EFFECTIVE',1,1000,NOW(),'0',
   'CORP_TIME_DEPOSIT','对公定期存款','DEPOSIT',1.580000,'HIGHER_BETTER','2026-08-01 00:00:00',NULL,1000,1000,NOW()),
  (9212,'000000','PLR20260806012',1001,'EFFECTIVE',1,1000,NOW(),'0',
   'AGREEMENT_DEPOSIT','协定存款','DEPOSIT',0.250000,'HIGHER_BETTER','2026-08-01 00:00:00',NULL,1000,1000,NOW()),
  (9213,'000000','PLR20260806013',1001,'EFFECTIVE',1,1000,NOW(),'0',
   'NOTICE_DEPOSIT','通知存款','DEPOSIT',0.350000,'HIGHER_BETTER','2026-08-01 00:00:00',NULL,1000,1000,NOW()),
  (9214,'000000','PLR20260806014',1001,'EFFECTIVE',1,1000,NOW(),'0',
   'BILL_MARGIN','银行承兑汇票保证金','MARGIN',1.050000,'HIGHER_BETTER','2026-08-01 00:00:00',NULL,1000,1000,NOW()),
  (9215,'000000','PLR20260806015',1001,'EFFECTIVE',1,1000,NOW(),'0',
   'CREDIT_MARGIN','信用证保证金','MARGIN',1.050000,'HIGHER_BETTER','2026-08-01 00:00:00',NULL,1000,1000,NOW())
ON DUPLICATE KEY UPDATE hard_boundary_rate=VALUES(hard_boundary_rate);

-- ---------- 7. 授信协议(dw_credit_agreement_snapshot,审批详情授信信息卡/存量合同自动带出) ----------
INSERT INTO dw_credit_agreement_snapshot (etl_md5,data_dt,agreement_no,customer_no,agreement_type,credit_amount,used_amount,available_amount,currency,start_date,end_date,agreement_status) VALUES
(5301,CURDATE(),'AGR-C001-2026','CUST001','COMPREHENSIVE',8000.0000,4800.0000,3200.0000,'CNY','2026-01-01','2026-12-31','EFFECTIVE'),
(5302,CURDATE(),'AGR-C002-2026','CUST002','COMPREHENSIVE',5000.0000,3000.0000,2000.0000,'CNY','2026-01-01','2026-12-31','EFFECTIVE'),
(5303,CURDATE(),'AGR-C101-2026','CUST101','SINGLE',800.0000,480.0000,320.0000,'CNY','2026-02-01','2027-01-31','EFFECTIVE'),
(5304,CURDATE(),'AGR-MA-2026','MEMBER_A','COMPREHENSIVE',6000.0000,3000.0000,3000.0000,'CNY','2026-01-01','2026-12-31','EFFECTIVE'),
(5305,CURDATE(),'AGR-MB-2026','MEMBER_B','COMPREHENSIVE',4000.0000,2000.0000,2000.0000,'CNY','2026-01-01','2026-12-31','EFFECTIVE')
ON DUPLICATE KEY UPDATE used_amount=VALUES(used_amount);

-- ---------- 8. 授信协议扩展场景(2026-08-10,授信信息卡/选择器测试) ----------
-- 场景覆盖:多协议选择、单笔单批、循环授信、已到期、额度用尽
-- CUST001 增加第二份协议(循环授信):验证下拉多选与切换带出
INSERT INTO dw_credit_agreement_snapshot (etl_md5,data_dt,agreement_no,customer_no,agreement_type,credit_amount,used_amount,available_amount,currency,start_date,end_date,agreement_status) VALUES
(5311,CURDATE(),'AGR-C001-2026B','CUST001','REVOLVING',2000.0000,500.0000,1500.0000,'CNY','2026-03-01','2027-02-28','EFFECTIVE'),
-- CUST002 第二份协议(单笔单批)
(5312,CURDATE(),'AGR-C002-2026B','CUST002','SINGLE',1000.0000,0.0000,1000.0000,'CNY','2026-06-01','2026-11-30','EFFECTIVE'),
-- CUST003 已到期协议(验证到期状态展示)
(5313,CURDATE(),'AGR-C003-2025','CUST003','COMPREHENSIVE',2000.0000,1500.0000,500.0000,'CNY','2025-01-01','2025-12-31','EXPIRED'),
-- CUST003 有效协议(额度已用尽:可用 0)
(5314,CURDATE(),'AGR-C003-2026','CUST003','COMPREHENSIVE',3000.0000,3000.0000,0.0000,'CNY','2026-01-01','2026-12-31','EFFECTIVE'),
-- CUST101 第二份协议(个人,单笔单批小额)
(5315,CURDATE(),'AGR-C101-2026B','CUST101','SINGLE',200.0000,50.0000,150.0000,'CNY','2026-05-01','2027-04-30','EFFECTIVE')
ON DUPLICATE KEY UPDATE used_amount=VALUES(used_amount);

-- ---------- 9. 合同关联抵押物/保证人(按类型带 ext_json 特有元素;贷款合同→担保一对一) ----------
-- CUST001 LC20260001:厂房(带面积/产权证号) + 保证人
INSERT INTO dw_mortgage_snapshot (etl_md5,data_dt,cust_no,contract_no,mortgage_type,mortgage_name,owner_name,owner_cert_no,register_no,assess_value,assess_date,mortgage_ratio,mortgage_addr,ext_json) VALUES
(5401,CURDATE(),'CUST001','LC20260001','FACTORY','某某工业园3号厂房','江苏某某科技有限公司','91320000XXXXXXXXX9','苏(2026)宜兴不动产权第0001号',6500.0000,'2026-01-05',60.0000,'宜兴市XX工业园3号',JSON_OBJECT('area','4200','certNo','苏(2026)宜兴不动产权第0001号')),
(5402,CURDATE(),'CUST002','LC20260002','VEHICLE','重型半挂牵引车','宜兴市某某制造有限公司','91320000XXXXXXXXX8','机动车登记第3301号',180.0000,'2026-02-10',50.0000,'宜兴市XX厂区',JSON_OBJECT('plateNo','苏B12345','vin','LFV2A21KXG000001','regDate','2023-06-15')),
(5403,CURDATE(),'CUST002','LC20260002','EQUIPMENT','数控加工中心2台','宜兴市某某制造有限公司','91320000XXXXXXXXX8','动抵登2026-007',900.0000,'2026-02-12',45.0000,'宜兴市XX路2号车间',JSON_OBJECT('specModel','VMC-850','quantity','2','purchaseDate','2021-03-20')),
(5404,CURDATE(),'MEMBER_A','CONTRACT_A001','LAND','城东工业用地','江苏某某控股电气有限公司','91320000XXXXXXXXX6','苏(2026)宜兴不动产权第0002号',4000.0000,'2026-01-20',55.0000,'城东工业园东区',JSON_OBJECT('area','15000','landUseType','出让','landUseExpiry','2068-05-30'))
ON DUPLICATE KEY UPDATE assess_value=VALUES(assess_value);

INSERT INTO dw_guarantor_snapshot (etl_md5,data_dt,cust_no,contract_no,guarantor_name,guarantor_cert_type,guarantor_cert_no,guarantee_type,guarantee_amount,guarantee_balance) VALUES
(5411,CURDATE(),'CUST001','LC20260001','江苏某某担保有限公司','UNIFIED','91320000YYYYYYYY01','JOINT',3000.0000,2800.0000),
(5412,CURDATE(),'CUST101','LC20260003','张某个体','ID','320***********5678','GENERAL',200.0000,180.0000)
ON DUPLICATE KEY UPDATE guarantee_balance=VALUES(guarantee_balance);
