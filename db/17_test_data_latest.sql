-- ============================================================
-- 手工测试数据集·增补(2026-08-12,配合 docs/06_测试方案.md §11 / docs/07_手工测试用例.md 场景 M)
-- 覆盖最新调整:①授信信息手工补录(#74) ②担保方式收敛 4 种存量展示(#76)
-- ③数据中心动态监控(#42) ④申请页精简(#73)
-- 幂等:全部 ON DUPLICATE KEY / DELETE+INSERT;data_dt 用 CURDATE()
-- 执行:docker exec -i ccr-mysql mysql -uroot -proot123 --default-character-set=utf8mb4 ccr_rate < db/17_test_data_latest.sql
-- ============================================================

USE `ccr_rate`;

-- ---------- 1. 授信补录:新增授信「无数仓协议」客户 CUST004 ----------
-- 场景:新增授信业务类型下,数仓无授信协议可带出 → 需手工补录完整协议要素(协议号可空)。
-- CUST004 仅存在于数仓客户主数据与征信,无 dw_credit_agreement_snapshot 行(无数仓协议)。
DELETE FROM dw_credit_agreement_snapshot WHERE customer_no='CUST004';
DELETE FROM caps_corp_cust_basic_info WHERE cust_no='CUST004';
INSERT INTO caps_corp_cust_basic_info (etl_md5,data_dt,cust_no,cust_name,cert_tp,cert_no,ffthlv_class,entp_charic,entp_scale,blgd_idsty,crdt_grd,entp_empe_num,rest_addr,rest_asts,estp_estb_dt,openact_org_no,openact_org_nm,openact_dt,basic_account_no,cust_class) VALUES
('md5_corp_c004',CURDATE(),'CUST004','宜兴某某环保科技有限公司','UNIFIED','91320000XXXXXXXXX5','正常','NON_SOE','小型','环保','A',60,'宜兴市XX路9号',900.0000,'2022-04-01',1001,'城东支行','2022-05-01','110066000006','EXISTING')
ON DUPLICATE KEY UPDATE cust_name=VALUES(cust_name), basic_account_no=VALUES(basic_account_no);

-- 征信(质量校验用,否则提交被 BLOCK)
INSERT INTO dw_credit_report_snapshot (etl_md5,data_dt,cust_no,report_date,parse_status) VALUES
(5401,CURDATE(),'CUST004',CURDATE(),'COMPLETE')
ON DUPLICATE KEY UPDATE parse_status=VALUES(parse_status);

-- ---------- 2. 授信补录:存量协议「可修正」数据(挂 CUST001 第二份循环授信,复核 #74 存量可修正) ----------
-- AGR-C001-2026B 已存在(15_test_data 第8节);此处补一份「已用额度接近授信」的循环授信,
-- 便于审批详情核验补录修正值优先展示(source=APPLICATION)。
INSERT INTO dw_credit_agreement_snapshot (etl_md5,data_dt,agreement_no,customer_no,agreement_type,credit_amount,used_amount,available_amount,currency,start_date,end_date,agreement_status) VALUES
(5411,CURDATE(),'AGR-C001-2026C','CUST001','REVOLVING',3000.0000,2900.0000,100.0000,'CNY','2026-07-01','2027-06-30','EFFECTIVE')
ON DUPLICATE KEY UPDATE used_amount=VALUES(used_amount);

-- ---------- 3. 担保方式收敛 4 种(#76):存量「保证金类」措施中文回显数据 ----------
-- 场景:担保方式下拉收敛为 抵押/质押/保证/信用 后,历史存量措施若为 BILL_MARGIN(银票保证金)等
-- 编码,审批详情/档案页仍需按 GUARANTEE_NAME_MAP 中文展示,不因收敛丢失。
-- 找一个已终态(CCR202608114AE7 FINAL)申请的分项担保组合 package_id 挂一条 BILL_MARGIN 存量措施。
INSERT INTO ccr_guarantee_measure
  (id,tenant_id,business_no,org_id,status,version_no,create_dept,create_by,create_time,del_flag,
   package_id,measure_no,measure_type,guarantor_customer_no,collateral_no,guarantee_amount,currency,ext_json)
SELECT 90220001,'000000',a.application_no,1001,'ACTIVE',1,1000,1000,NOW(),'0',
       p.id,'MS-BILL-001','BILL_MARGIN',NULL,'COLL-2026-001',500.0000,'CNY',
       JSON_OBJECT('marginRatio',0.50,'billNo','EBC2026xxxxx1')
FROM ccr_application a
JOIN ccr_pricing_item pi ON pi.application_id = a.id
JOIN ccr_guarantee_package p ON p.pricing_item_id = pi.id
WHERE a.application_no='CCR202608114AE7'
LIMIT 1
ON DUPLICATE KEY UPDATE guarantee_amount=VALUES(guarantee_amount);

-- ---------- 4. 数据中心动态监控(#42):动态表清单样例数据已就位(19 张数仓表齐全) ----------
-- dw_credit_agreement_snapshot(授信协议,A11)数据已在第1/2节写入;dw_org_dim(弃用表)仍在库
-- 但动态监控会排除它。测试脚本可直接刷新全部数仓表 data_dt 防 3 天时效阻断(与 15_test_data 同)。
UPDATE dw_credit_agreement_snapshot SET data_dt=CURDATE();

-- ---------- 5. 申请页精简(#73):验证数据复用 15_test_data(CUST001/CUST101/CUST002) ----------
-- 申请要素区只保留「业务类型」(删贷款品种/金额档),前端断言即可,无需新数据。
