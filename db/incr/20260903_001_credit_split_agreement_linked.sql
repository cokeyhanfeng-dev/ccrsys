-- 20260903_001 #473 拆分项归属授信协议:存量「换授信协议 → 拆分项随协议切分」验证数据
-- 契约口径(开发阶段约定,数仓对接前需复核):dw_credit_split_snapshot.credit_no = 所属授信协议编号,
--   即与 dw_credit_agreement_snapshot.agreement_no 同一编号体系(授信与协议一一对应,拆分项顶层授信=协议)。
--   原 mock 拆分挂 CR-C001(数仓无此协议)导致前端无法按所选协议切分拆分项。
-- 本增量:
--   ① 修正 CUST001 既有拆分 SPLIT-C001-001/002(880001/880002)credit_no CR-C001 → AGR-C001-2026(综合 8000 万);
--   ② 新增 AGR-C001-2026B(循环 2000 万)名下 2 个拆分项 SPLIT-C001-101/102 + 措施,用于「换协议→拆分项替换」演示;
--   ③ 相关行 data_dt 统一刷到 CURDATE(),保证同批可见(查询取 MAX(data_dt) 批)。
-- 幂等:UPDATE 按 split_no 重复执行无副作用;INSERT 显式 etl_md5 + ON DUPLICATE KEY UPDATE 收敛(重复执行仅刷 data_dt)。
-- 执行:docker exec -i ccr-mysql mysql -uroot -proot123 --default-character-set=utf8mb4 ccr_rate < db/incr/20260903_001_credit_split_agreement_linked.sql

USE ccr_rate;

-- ① 归属修正(既有 2 拆分归属综合授信协议 AGR-C001-2026;担保方式/金额/原利率不变)
UPDATE dw_credit_split_snapshot
   SET credit_no = 'AGR-C001-2026', data_dt = CURDATE()
 WHERE split_no IN ('SPLIT-C001-001', 'SPLIT-C001-002');

-- ② AGR-C001-2026B 名下拆分项(担保方式拆分:抵押 1500 + 保证 500)
INSERT INTO dw_credit_split_snapshot
  (etl_md5, data_dt, split_no, cust_no, credit_no, contract_no, guarantee_type, split_amount, currency, original_rate, maturity_date, split_status)
VALUES
  (880101, CURDATE(), 'SPLIT-C001-101', 'CUST001', 'AGR-C001-2026B', NULL, 'MORTGAGE', 1500, 'CNY', 3.70, DATE_ADD(CURDATE(), INTERVAL 1 YEAR), 'EFFECTIVE'),
  (880102, CURDATE(), 'SPLIT-C001-102', 'CUST001', 'AGR-C001-2026B', NULL, 'GUARANTEE', 500, 'CNY', 3.95, DATE_ADD(CURDATE(), INTERVAL 1 YEAR), 'EFFECTIVE')
ON DUPLICATE KEY UPDATE data_dt = VALUES(data_dt), credit_no = VALUES(credit_no);

-- ③ 措施明细:101 抵押(住宅);102 保证(连带保证人)
INSERT INTO dw_credit_split_measure_snapshot
  (etl_md5, data_dt, split_no, measure_type, mortgage_type, mortgage_name, owner_name, owner_cert_no, register_no,
   assess_value, mortgage_ratio, mortgage_addr, guarantor_name, guarantor_cert_type, guarantor_cert_no,
   guarantee_type, guarantee_amount, guarantee_balance, measure_name, collateral_no, currency, ext_json)
VALUES
  (881101, CURDATE(), 'SPLIT-C001-101', 'MORTGAGE', 'HOUSE', '宜兴城南新苑住宅', '王五', '320282197507071122', '苏(2022)宜兴不动产权第0000333号',
   1200, 70, '宜兴市城南街道新苑路9号', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'CNY', JSON_OBJECT('area', 138)),
  (881102, CURDATE(), 'SPLIT-C001-102', 'GUARANTEE', NULL, NULL, NULL, NULL, NULL,
   NULL, NULL, NULL, '赵六', 'ID_CARD', '320282199001011234', 'JOINT', 500, 500, NULL, NULL, 'CNY', NULL)
ON DUPLICATE KEY UPDATE data_dt = VALUES(data_dt);
