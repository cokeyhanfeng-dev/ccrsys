-- ============================================================
-- 模拟数仓数据(开发期;数仓推送后替换)
-- 填充外部数据表(dw_*/caps_*),支撑申请带出/审批参考完整跑通
-- ============================================================

USE `ccr_rate`;

-- ---------- 对公客户主数据(caps_corp) ----------
INSERT INTO caps_corp_cust_basic_info (etl_md5,data_dt,cust_no,cust_name,cert_tp,cert_no,ffthlv_class,entp_charic,entp_scale,blgd_idsty,crdt_grd,entp_empe_num,rest_addr,rest_asts,estp_estb_dt,openact_org_no,openact_org_nm,openact_dt,basic_account_no,cust_class) VALUES
('md5_corp_001',CURDATE(),'CUST001','江苏某某科技有限公司','UNIFIED','91320000XXXXXXXXX9','正常','NON_SOE','大型','高端装备制造','AA',1200,'南京市XX路1号',5000.0000,'2015-03-01',1001,'城东支行','2016-06-12','110066000001','EXISTING'),
('md5_corp_002',CURDATE(),'CUST002','宜兴市某某制造有限公司','UNIFIED','91320000XXXXXXXXX8','正常','SOE','中型','纺织制造','A+',800,'宜兴市XX路2号',3000.0000,'2010-01-01',1002,'城西支行','2011-05-20','110066000002','EXISTING'),
('md5_corp_003',CURDATE(),'CUST003','江阴某某装备有限公司','UNIFIED','91320000XXXXXXXXX7','关注','NON_SOE','中型','高端装备制造','BBB',600,'江阴市XX路3号',2000.0000,'2018-08-01',1001,'城东支行','2019-03-15',NULL,'NEW')
ON DUPLICATE KEY UPDATE cust_name=VALUES(cust_name), basic_account_no=VALUES(basic_account_no);

-- ---------- 对私客户主数据(caps_indv) ----------
INSERT INTO caps_indv_cust_basic_info (etl_md5,data_dt,cust_no,cust_nm,cert_tp,cert_no,gnd,ffthlv_class,ocupn,whlyr_incm,mrrg_sittn,rsd_addr,tel_no,opnact_org_no,opnact_org_nm,opnact_dt,cust_class) VALUES
('md5_indv_001',CURDATE(),'CUST101','张三','ID','320***********1234','男','正常','个体经营',80.0000,'已婚','南京市XX小区',13800000001,1001,'城东支行','2018-01-01','EXISTING')
ON DUPLICATE KEY UPDATE cust_nm=VALUES(cust_nm);

-- ---------- 本行融资(原 dw_own_financing 已并入贷款合同,2026-08-11 去冗余) ----------
-- 存量贷款调息数据统一从 dw_loan_contract_snapshot 取(borrower_customer_no 匹配):
-- 原利率=execution_rate、担保类型=guarantee_type、余额=contract_balance;
-- 单户存量合同见 15_test_data.sql(LC20260001-03),本文件不再单独 mock

-- ---------- 当前贡献度(dw_contribution_metric,对公8项+TOTAL;TOTAL行=同客户明细行之和,勾稽一致) ----------
INSERT INTO dw_contribution_metric (etl_md5,data_dt,cust_no,metric_code,metric_name,metric_value,value_type,metric_scope,stat_start,stat_end,calc_version) VALUES
(1001,CURDATE(),'CUST001','TOTAL','综合贡献总额',130.3000,'CONTRIBUTION_AMOUNT','PUBLIC','2026-07-01','2026-07-31','V1.0'),
(1002,CURDATE(),'CUST001','PUBLIC_DEPOSIT_AVG','存款日均',86.5000,'AVG_BALANCE','PUBLIC','2026-07-01','2026-07-31','V1.0'),
(1003,CURDATE(),'CUST001','PUBLIC_PROJECT_LOAN_AVG','项目贷日均',15.8000,'AVG_BALANCE','PUBLIC','2026-07-01','2026-07-31','V1.0'),
(1004,CURDATE(),'CUST001','PUBLIC_DISCOUNT_SPREAD','贴现规模',8.2000,'AVG_BALANCE','PUBLIC','2026-07-01','2026-07-31','V1.0'),
(1005,CURDATE(),'CUST001','PUBLIC_OFF_BALANCE_INCOME','中间业务收入',6.5000,'INCOME','PUBLIC','2026-07-01','2026-07-31','V1.0'),
(1006,CURDATE(),'CUST001','PUBLIC_EXCHANGE_SPREAD','结售汇业务总量',4.1000,'AVG_BALANCE','PUBLIC','2026-07-01','2026-07-31','V1.0'),
(1007,CURDATE(),'CUST001','PUBLIC_PAYROLL_CONTRIBUTION','代发客户数',2.3000,'CONTRIBUTION_AMOUNT','PUBLIC','2026-07-01','2026-07-31','V1.0'),
(1008,CURDATE(),'CUST001','PUBLIC_PAYROLL_AMOUNT','代发金额',3.8000,'CONTRIBUTION_AMOUNT','PUBLIC','2026-07-01','2026-07-31','V1.0'),
(1009,CURDATE(),'CUST001','PUBLIC_WEALTH_INCOME','对公财富中收',3.1000,'INCOME','PUBLIC','2026-07-01','2026-07-31','V1.0')
ON DUPLICATE KEY UPDATE metric_value=VALUES(metric_value);

-- ---------- 他行融资概要/明细(dw_credit_financing) ----------
INSERT INTO dw_credit_financing_summary (etl_md5,data_dt,report_id,cust_no,lender_count,npl_balance,credit_amount_total,used_amount_total,loan_account_count,overdue_account_count,overdue_balance,special_mention_balance,external_guarantee_balance) VALUES
(2001,CURDATE(),5001,'CUST001',3,300.0000,8000.0000,5200.0000,5,1,200.0000,150.0000,500.0000)
ON DUPLICATE KEY UPDATE lender_count=VALUES(lender_count);

INSERT INTO dw_credit_financing_detail (etl_md5,data_dt,report_id,customer_no,lender_name,credit_amount,used_amount,balance_amount,annual_rate) VALUES
(2101,CURDATE(),5001,'CUST001','工商银行',2000.0000,1500.0000,1200.0000,3.5000),
(2102,CURDATE(),5001,'CUST001','农业银行',1500.0000,1000.0000,800.0000,3.6000),
(2103,CURDATE(),5001,'CUST001','中国银行',1000.0000,600.0000,500.0000,3.7000)
ON DUPLICATE KEY UPDATE lender_name=VALUES(lender_name);

-- ---------- 机构达成(dw_org_performance) ----------
INSERT INTO dw_org_performance_snapshot (etl_md5,data_dt,org_code,stat_month,achieved_amount,expected_amount,completion_rate,snapshot_ts) VALUES
(3001,CURDATE(),'1001','202607',1828.0000,2000.0000,0.9140,NOW())
ON DUPLICATE KEY UPDATE completion_rate=VALUES(completion_rate);

-- ============================================================
-- 集团演示数据(V1.0 §10.2/A.5)
-- 集团GROUP001(批复1亿) - 成员MEMBER_A(EXCLUSIVE 6000万)/MEMBER_B(EXCLUSIVE 4000万)
-- 额度勾稽: 集团已用5000 = A已用3000 + B已用2000
-- 贡献度勾稽: 集团TOTAL(130) = 成员A(80) + 成员B(50);成员TOTAL = 各自明细行之和
-- 防重复归集: 集团行scope=GROUP、成员行scope=GROUP_MEMBER,集团与成员分口径存储不重复合计
-- ============================================================

-- ---------- 集团成员客户主数据(caps_corp) ----------
INSERT INTO caps_corp_cust_basic_info (etl_md5,data_dt,cust_no,cust_name,cert_tp,cert_no,ffthlv_class,entp_charic,entp_scale,blgd_idsty,crdt_grd,entp_empe_num,rest_addr,rest_asts,estp_estb_dt,openact_org_no,openact_org_nm,openact_dt,basic_account_no,cust_class) VALUES
('md5_corp_004',CURDATE(),'MEMBER_A','江苏某某控股电气有限公司','UNIFIED','91320000XXXXXXXXX6','正常','NON_SOE','大型','电气设备制造','AA-',900,'南京市XX工业园10号',8000.0000,'2012-05-01',1001,'城东支行','2013-04-10','110066000003','EXISTING'),
('md5_corp_005',CURDATE(),'MEMBER_B','江苏某某控股新材料有限公司','UNIFIED','91320000XXXXXXXXX5','正常','NON_SOE','中型','新材料制造','A',500,'无锡市XX工业园20号',4000.0000,'2016-09-01',1001,'城东支行','2017-02-15','110066000004','EXISTING')
ON DUPLICATE KEY UPDATE cust_name=VALUES(cust_name), basic_account_no=VALUES(basic_account_no);

-- ---------- 集团主数据(dw_customer_group_snapshot) ----------
INSERT INTO dw_customer_group_snapshot (etl_md5,data_dt,group_no,group_name,group_type,manager_org_id,group_status) VALUES
(4001,CURDATE(),'GROUP001','江苏某某控股集团','INDUSTRY_GROUP','1001','NORMAL')
ON DUPLICATE KEY UPDATE group_name=VALUES(group_name);

-- ---------- 集团成员(dw_customer_group_member_snapshot) ----------
INSERT INTO dw_customer_group_member_snapshot (etl_md5,data_dt,group_no,member_customer_no,member_role,control_relation,is_core_member,relation_start,relation_end) VALUES
(4011,CURDATE(),'GROUP001','MEMBER_A','CORE','控股','Y','2018-01-01',NULL),
(4012,CURDATE(),'GROUP001','MEMBER_B','GENERAL','控股','N','2019-06-01',NULL)
ON DUPLICATE KEY UPDATE member_role=VALUES(member_role);

-- ---------- 客户关系(dw_customer_relation_snapshot) ----------
INSERT INTO dw_customer_relation_snapshot (etl_md5,data_dt,customer_no,related_customer_no,relation_type,relation_strength,relation_start,relation_end,relation_status) VALUES
(4021,CURDATE(),'MEMBER_A','MEMBER_B','GROUP_MEMBER','STRONG','2019-06-01',NULL,'VALID')
ON DUPLICATE KEY UPDATE relation_status=VALUES(relation_status);

-- ---------- 集团授信(dw_group_credit_snapshot,批复1亿) ----------
INSERT INTO dw_group_credit_snapshot (etl_md5,data_dt,group_credit_no,group_no,approved_total_amount,allocated_amount,used_amount,available_amount,currency,credit_start,credit_end,revolving_flag,credit_status) VALUES
(4101,CURDATE(),'GCREDIT001','GROUP001',10000.0000,10000.0000,5000.0000,5000.0000,'CNY','2026-01-01','2026-12-31','Y','EFFECTIVE')
ON DUPLICATE KEY UPDATE used_amount=VALUES(used_amount);

-- ---------- 成员额度(dw_member_credit_limit_snapshot,EXCLUSIVE 6000/4000) ----------
INSERT INTO dw_member_credit_limit_snapshot (etl_md5,data_dt,member_limit_no,group_credit_no,member_customer_no,allocation_mode,allocated_amount,used_amount,available_amount,shared_limit_group_no,currency,limit_start,limit_end,limit_status) VALUES
(4111,CURDATE(),'MLIMIT001','GCREDIT001','MEMBER_A','EXCLUSIVE',6000.0000,3000.0000,3000.0000,NULL,'CNY','2026-01-01','2026-12-31','EFFECTIVE'),
(4112,CURDATE(),'MLIMIT002','GCREDIT001','MEMBER_B','EXCLUSIVE',4000.0000,2000.0000,2000.0000,NULL,'CNY','2026-01-01','2026-12-31','EFFECTIVE')
ON DUPLICATE KEY UPDATE used_amount=VALUES(used_amount);

-- ---------- 用信分项(dw_credit_tranche_snapshot,每成员1条) ----------
INSERT INTO dw_credit_tranche_snapshot (etl_md5,data_dt,tranche_no,member_limit_no,member_customer_no,product_code,main_guarantee_type,tranche_amount,used_amount,available_amount,currency,term_start,term_end,tranche_status) VALUES
(4121,CURDATE(),'TRANCHE001','MLIMIT001','MEMBER_A','LOAN_GENERAL','CREDIT',3000.0000,2000.0000,1000.0000,'CNY','2026-01-01','2026-12-31','EFFECTIVE'),
(4122,CURDATE(),'TRANCHE002','MLIMIT002','MEMBER_B','LOAN_GENERAL','MORTGAGE',2000.0000,1500.0000,500.0000,'CNY','2026-01-01','2026-12-31','EFFECTIVE')
ON DUPLICATE KEY UPDATE used_amount=VALUES(used_amount);

-- ---------- 贷款合同(dw_loan_contract_snapshot,每成员1份) ----------
-- agreement_no 关联授信协议(AGR-MA-2026/AGR-MB-2026);guarantee_type 取自用信分项主担保
INSERT INTO dw_loan_contract_snapshot (etl_md5,data_dt,contract_no,agreement_no,tranche_no,borrower_customer_no,contract_amount,contract_balance,guarantee_type,currency,execution_rate,rate_type,lpr_term,start_date,maturity_date,contract_status,contract_version) VALUES
(4131,CURDATE(),'CONTRACT_A001','AGR-MA-2026','TRANCHE001','MEMBER_A',2000.0000,2000.0000,'CREDIT','CNY',3.6000,'LPR_PLUS','1Y','2026-02-01','2027-02-01','EFFECTIVE',1),
(4132,CURDATE(),'CONTRACT_B001','AGR-MB-2026','TRANCHE002','MEMBER_B',1500.0000,1500.0000,'MORTGAGE','CNY',3.8000,'LPR_PLUS','1Y','2026-03-01','2027-03-01','EFFECTIVE',1)
ON DUPLICATE KEY UPDATE contract_balance=VALUES(contract_balance);

-- ---------- 借据(dw_loan_note_snapshot,每合同2笔+1笔利率不一致用于核验异常演示) ----------
-- NB_A003 执行利率3.9000 <> 合同CONTRACT_A001利率3.6000,供决议核验RECONCILE_EXCEPTION演示
INSERT INTO dw_loan_note_snapshot (etl_md5,data_dt,loan_note_no,agreement_no,contract_no,tranche_no,borrower_customer_no,loan_amount,loan_balance,currency,execution_rate,rate_type,lpr_term,start_date,maturity_date,note_status) VALUES
(4141,CURDATE(),'NB_A001','AGR-MA-2026','CONTRACT_A001','TRANCHE001','MEMBER_A',1000.0000,1000.0000,'CNY',3.6000,'LPR_PLUS','1Y','2026-02-05','2027-02-01','NORMAL'),
(4142,CURDATE(),'NB_A002','AGR-MA-2026','CONTRACT_A001','TRANCHE001','MEMBER_A',700.0000,700.0000,'CNY',3.6000,'LPR_PLUS','1Y','2026-03-10','2027-02-01','NORMAL'),
(4143,CURDATE(),'NB_A003','AGR-MA-2026','CONTRACT_A001','TRANCHE001','MEMBER_A',300.0000,300.0000,'CNY',3.9000,'LPR_PLUS','1Y','2026-04-15','2027-02-01','NORMAL'),
(4144,CURDATE(),'NB_B001','AGR-MB-2026','CONTRACT_B001','TRANCHE002','MEMBER_B',900.0000,900.0000,'CNY',3.8000,'LPR_PLUS','1Y','2026-03-05','2027-03-01','NORMAL'),
(4145,CURDATE(),'NB_B002','AGR-MB-2026','CONTRACT_B001','TRANCHE002','MEMBER_B',600.0000,600.0000,'CNY',3.8000,'LPR_PLUS','1Y','2026-04-01','2027-03-01','NORMAL')
ON DUPLICATE KEY UPDATE loan_balance=VALUES(loan_balance);

-- ---------- 存款账户(dw_deposit_account_snapshot) ----------
-- 演示账号(sha256 对应): MEMBER_A=9550880000000001, MEMBER_B=9550880000000002, CUST001=9550880000000101
INSERT INTO dw_deposit_account_snapshot (etl_md5,data_dt,deposit_account_no_cipher,deposit_account_hash,customer_no,product_code,account_balance,currency,execution_rate,rate_type,term_value,term_unit,open_date,maturity_date,account_status) VALUES
(4151,CURDATE(),'CIPHER_9550880000000001','0746aa12985c9a5e6571846fcebd4ccde73c4a41bd00bc693eb779599c138940','MEMBER_A','CORP_TIME_DEPOSIT',800.0000,'CNY',1.5000,'FIXED',1,'YEAR','2026-01-10','2027-01-10','NORMAL'),
(4152,CURDATE(),'CIPHER_9550880000000002','0878fa9dce07097a550ba38ba4a8e18e2771541822a1252e7223c028512d23cd','MEMBER_B','CORP_TIME_DEPOSIT',500.0000,'CNY',1.5000,'FIXED',1,'YEAR','2026-02-20','2027-02-20','NORMAL'),
(4153,CURDATE(),'CIPHER_9550880000000101','a5a5de58e42ace7889dbcc4bf45372c6d7014983a9104921dbe6583c72523c10','CUST001','CORP_TIME_DEPOSIT',1200.0000,'CNY',1.2500,'FIXED',1,'YEAR','2026-03-05','2027-03-05','NORMAL')
ON DUPLICATE KEY UPDATE account_balance=VALUES(account_balance);

-- ---------- 集团/成员贡献度(dw_contribution_metric,GROUP/GROUP_MEMBER口径) ----------
-- 勾稽: 成员TOTAL=各自明细行之和(A:50+30=80,B:30+20=50);集团TOTAL=成员合计(80+50=130)
INSERT INTO dw_contribution_metric (etl_md5,data_dt,cust_no,metric_code,metric_name,metric_value,value_type,metric_scope,stat_start,stat_end,calc_version) VALUES
(4201,CURDATE(),'GROUP001','TOTAL','集团综合贡献总额',130.0000,'CONTRIBUTION_AMOUNT','GROUP','2026-07-01','2026-07-31','V1.0'),
(4211,CURDATE(),'MEMBER_A','TOTAL','成员综合贡献总额',80.0000,'CONTRIBUTION_AMOUNT','GROUP_MEMBER','2026-07-01','2026-07-31','V1.0'),
(4212,CURDATE(),'MEMBER_A','GM_LOAN_CONTRIBUTION','贷款贡献',50.0000,'CONTRIBUTION_AMOUNT','GROUP_MEMBER','2026-07-01','2026-07-31','V1.0'),
(4213,CURDATE(),'MEMBER_A','GM_DEPOSIT_CONTRIBUTION','存款贡献',30.0000,'CONTRIBUTION_AMOUNT','GROUP_MEMBER','2026-07-01','2026-07-31','V1.0'),
(4221,CURDATE(),'MEMBER_B','TOTAL','成员综合贡献总额',50.0000,'CONTRIBUTION_AMOUNT','GROUP_MEMBER','2026-07-01','2026-07-31','V1.0'),
(4222,CURDATE(),'MEMBER_B','GM_LOAN_CONTRIBUTION','贷款贡献',30.0000,'CONTRIBUTION_AMOUNT','GROUP_MEMBER','2026-07-01','2026-07-31','V1.0'),
(4223,CURDATE(),'MEMBER_B','GM_DEPOSIT_CONTRIBUTION','存款贡献',20.0000,'CONTRIBUTION_AMOUNT','GROUP_MEMBER','2026-07-01','2026-07-31','V1.0')
ON DUPLICATE KEY UPDATE metric_value=VALUES(metric_value);
