-- ============================================================
-- 手工测试数据集·多分项专项(2026-08-13,配合 docs/07_手工测试用例.md 场景 N)
-- 覆盖:①存量多分项(X002 协议下多个贷款合同一次调息) ②新增多分项(新增授信按担保方式切分)
-- 说明:一笔申请业务类型为申请级全局(EXISTING 存量调息 / NEW 新增授信),存量+新增不可共存,
--       故拆两笔:存量多分项(场景 N1)+新增多分项(场景 N2)。
-- 幂等:ON DUPLICATE KEY UPDATE;data_dt 用 CURDATE() 并整表刷新(合同查询按 MAX(data_dt) 取最新批次)
-- 执行:docker exec -i ccr-mysql mysql -uroot -proot123 --default-character-set=utf8mb4 ccr_rate < db/22_test_data_multi_item.sql
-- 注意:本文件新增合同 LC20260007 后,存量模式选 CUST002 会带出 2 个合同分项(LC20260002+LC20260007),
--       场景 B 单合同步骤可删除多余分项;不影响既有终态分项。
-- ============================================================

USE `ccr_rate`;

-- ---------- 1. 存量多分项:N1 前置,给 CUST002 的 X002 授信协议补第二个贷款合同 ----------
-- X002(综合授信 2000 万,已用 1500)下现有 LC20260002(4.20%);补 LC20260007(4.05%,抵押,500万/余额400万)。
-- 存量模式选 X002 协议 → 自动带出 2 个合同分项,一次申请对两个合同调息(多分项)。
INSERT INTO dw_loan_contract_snapshot (etl_md5,data_dt,contract_no,agreement_no,tranche_no,borrower_customer_no,contract_amount,contract_balance,guarantee_type,currency,execution_rate,rate_type,lpr_term,start_date,maturity_date,contract_status,contract_version)
VALUES (207,CURDATE(),'LC20260007','X002',NULL,'CUST002',500.0000,400.0000,'MORTGAGE','CNY',4.0500,'LPR_PLUS','1Y','2026-08-01','2027-07-31','EFFECTIVE',1)
ON DUPLICATE KEY UPDATE contract_balance=VALUES(contract_balance);

-- ---------- 2. 数仓快照整表刷新 data_dt ----------
-- 合同/授信/抵押物/保证人查询均按 data_dt=(SELECT MAX(data_dt)) 取最新批次;新插入行的 data_dt 为
-- 今日而旧行为昨日会导致 MAX 漂移,旧合同反而带不出来,故整表统一刷新到今日。
UPDATE dw_loan_contract_snapshot SET data_dt=CURDATE();
UPDATE dw_credit_agreement_snapshot SET data_dt=CURDATE();
UPDATE dw_mortgage_snapshot SET data_dt=CURDATE();
UPDATE dw_guarantor_snapshot SET data_dt=CURDATE();
UPDATE caps_corp_cust_basic_info SET data_dt=CURDATE();
