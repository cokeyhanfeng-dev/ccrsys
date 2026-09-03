-- 20260903_002 #474/#475 综合利率演示:干净存量客户 CUST009(申请页走通存量调息 + 实时综合利率)
-- 目的:验证「授信协议区默认展开 + 原执行/申请综合利率加权展示」,分母=当前授信总额(协议额度)。
--   为此特意让 AGR-C009-2026 协议额度 = 名下拆分项合计(5000 万),避免出现 CUST001 那种
--   协议 8000 / 拆分合计 1000 的综合利率被稀释失真;综合原执行 = (3000×3.85 + 1500×4.10 + 500×3.90)÷5000 = 3.93%。
-- 契约口径(dw_credit_split_snapshot.credit_no = 所属协议号,与 #473 一致):3 个拆分项全部挂 AGR-C009-2026。
-- 批次(关键):caps_corp_cust_basic_info / dw_credit_agreement_snapshot 两表当前 MAX(data_dt)=2026-09-01,
--   dw_credit_split_snapshot / dw_credit_split_measure_snapshot 两表当前 MAX(data_dt)=2026-09-03。
--   新行 data_dt 必须落在各表既有 MAX 上(不抬高批次),否则会破坏同表其它客户的可见性(查询按全表 MAX 批读)。
--   因此 corp/协议行停 09-01,拆分/措施行停 09-03;重复执行不抬批次(字面量而非 CURDATE)。
-- 幂等:全部显式 etl_md5 + ON DUPLICATE KEY UPDATE(重复执行仅刷 data_dt 等,不产生重复行)。
-- 执行:docker exec -i ccr-mysql mysql -uroot -proot123 --default-character-set=utf8mb4 ccr_rate < db/incr/20260903_002_cust009_blend_demo.sql

USE ccr_rate;

-- ① 客户主档 CUST009(管户 02301039 许欢,存量对公;NON_SOE 与 CUST001 同口径,矩阵边界行为一致)
INSERT INTO caps_corp_cust_basic_info
  (etl_md5, data_dt, cust_no, cust_name, cert_tp, cert_no, ffthlv_class, entp_charic, entp_scale, blgd_idsty,
   crdt_grd, entp_empe_num, rest_addr, rest_asts, estp_estb_dt, openact_org_no, openact_org_nm,
   openact_dt, basic_account_no, cust_class, mgr_no)
VALUES
  ('md5_corp_009', '2026-09-01', 'CUST009', '宜兴某某智能装备制造有限公司', 'UNIFIED', '91320282MA01C0009X',
   '正常', 'NON_SOE', '中型', '智能装备制造', 'AA', 860, '宜兴市环科园绿园路88号', 6500.0000, '2019-06-18',
   '3202230019', '城东支行', '2019-08-02', NULL, 'EXISTING', '02301039')
ON DUPLICATE KEY UPDATE data_dt = VALUES(data_dt), cust_name = VALUES(cust_name), mgr_no = VALUES(mgr_no);

-- ② 授信协议 AGR-C009-2026(综合 5000 万,额度 = 名下拆分合计,不稀释综合利率;全部可用未占用)
INSERT INTO dw_credit_agreement_snapshot
  (etl_md5, data_dt, agreement_no, customer_no, agreement_type, credit_amount, used_amount, available_amount,
   currency, start_date, end_date, agreement_status)
VALUES
  (900001, '2026-09-01', 'AGR-C009-2026', 'CUST009', 'COMPREHENSIVE', 5000.0000, 0.0000, 5000.0000,
   'CNY', '2026-01-01', '2026-12-31', 'EFFECTIVE')
ON DUPLICATE KEY UPDATE data_dt = VALUES(data_dt), credit_amount = VALUES(credit_amount),
                       available_amount = VALUES(available_amount);

-- ③ 拆分项(3 项合计 5000 = 协议额度;不同担保方式/原利率,演示综合加权与原/申请联动)
INSERT INTO dw_credit_split_snapshot
  (etl_md5, data_dt, split_no, cust_no, credit_no, contract_no, guarantee_type, split_amount, currency,
   original_rate, maturity_date, split_status)
VALUES
  (900101, '2026-09-03', 'SPLIT-C009-001', 'CUST009', 'AGR-C009-2026', NULL, 'MORTGAGE', 3000, 'CNY', 3.85, '2027-03-01', 'EFFECTIVE'),
  (900102, '2026-09-03', 'SPLIT-C009-002', 'CUST009', 'AGR-C009-2026', NULL, 'GUARANTEE', 1500, 'CNY', 4.10, '2027-03-01', 'EFFECTIVE'),
  (900103, '2026-09-03', 'SPLIT-C009-003', 'CUST009', 'AGR-C009-2026', NULL, 'MORTGAGE', 500, 'CNY', 3.90, '2027-03-01', 'EFFECTIVE')
ON DUPLICATE KEY UPDATE data_dt = VALUES(data_dt), credit_no = VALUES(credit_no);

-- ④ 拆分措施明细:001 抵押(住宅);002 保证(连带保证人);003 抵押(住宅第二处)
INSERT INTO dw_credit_split_measure_snapshot
  (etl_md5, data_dt, split_no, measure_type, mortgage_type, mortgage_name, owner_name, owner_cert_no, register_no,
   assess_value, mortgage_ratio, mortgage_addr, guarantor_name, guarantor_cert_type, guarantor_cert_no,
   guarantee_type, guarantee_amount, guarantee_balance, measure_name, collateral_no, currency, ext_json)
VALUES
  (900201, '2026-09-03', 'SPLIT-C009-001', 'MORTGAGE', 'HOUSE', '宜兴环科园湖滨御景住宅', '周立', '320282198512021133', '苏(2022)宜兴不动产权第0000777号',
   2000, 70, '宜兴市环科园湖滨路18号', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'CNY', JSON_OBJECT('area', 156)),
  (900202, '2026-09-03', 'SPLIT-C009-002', 'GUARANTEE', NULL, NULL, NULL, NULL, NULL,
   NULL, NULL, NULL, '郑海', 'ID_CARD', '320282197803151244', 'JOINT', 1500, 1500, NULL, NULL, 'CNY', NULL),
  (900203, '2026-09-03', 'SPLIT-C009-003', 'MORTGAGE', 'HOUSE', '宜兴城北和润华庭住宅', '孙梅', '320282198901203355', '苏(2023)宜兴不动产权第0000444号',
   350, 70, '宜兴市城北街道和润路6号', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'CNY', JSON_OBJECT('area', 92))
ON DUPLICATE KEY UPDATE data_dt = VALUES(data_dt);
