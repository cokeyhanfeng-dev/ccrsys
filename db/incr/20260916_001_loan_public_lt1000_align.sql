-- ============================================================
-- 对公 <1000万 贷款矩阵对齐 1000万~5000万 档
-- 生成日期:2026-09-16    类型:保留库增量(纯数据 DML,无表结构变更,无需重启服务)
--
-- 目标:把 amount_tier='LT_1000' 的对公贷款档,由「不分客户类型的一套」
--       改为「SOE / NON_SOE 两套」,边界与相邻档 GE_1000_LT_5000 完全对齐。
--
-- 改法(2026-09-16 用户拍板):
--   1) 现有 12 行(EXISTING 3 + NEW 9)由「不分客户类型」改为 NON_SOE,
--      边界/降幅照抄 GE_1000_LT_5000 档非国企行;
--   2) 新增 12 行 SOE(国企)变体,边界照抄 GE_1000_LT_5000 档国企行;
--   3) 唯一例外:存量(EXISTING)部门归属保留零售金融部 3202233991
--      (参照档为 公司金融部 3202233912);新增仍为授信评审部 3202233943;
--   4) 生效方式:直接改现有行、立即生效(用户选定,不版本化)。
--
-- 为何 matrix_no 不改名:
--   ccr_application.matched_matrix_no / ccr_pricing_item.matched_matrix_no
--   持久化了路由时命中的矩阵号,改名会断历史单溯源。故现有 12 行 matrix_no
--   原样保留(其中不带 SOE 后缀的那批即本次的 NON_SOE 行),
--   国企行按 M-PUB-*-LT1000-SOE-* 新起。
--
-- 参照行(LPR_V1 生效:1Y=3.000%、5Y=3.500%;下方括号内百分比按此换算,
--         仅为注释文案,运行时判定一律读 ccr_lpr_version 的生效行):
--   EXISTING NON_SOE DGM 原利率-20BP(下限3.0%) / VP 3.0%      SOE DGM -30BP / VP 3.0%
--   NEW NON_SOE 1Y.3Y: DGM LPR+40BP / VP LPR+20BP / 小组 LPR+0
--              5Y   : DGM LPR5Y+10BP / VP LPR5Y-10BP / 小组 LPR5Y-30BP
--   NEW SOE     1Y.3Y: DGM LPR+0 / VP LPR-10BP / 小组 LPR-20BP
--              5Y   : DGM LPR5Y-10BP / VP LPR5Y-20BP / 小组 LPR5Y-50BP
--
-- 影响范围:仅 LOAN_PUBLIC + amount_tier='LT_1000'。
--   个人贷款(LOAN_PERSONAL)分档、对公 ≥1000万 各档、存款 均不受影响。
--
-- 幂等:UPDATE 按 matrix_no 定位(另加档位限定防串档);INSERT 的 id 由
--       MAX(id) 自适应递推,不依赖生产具体 id 值;重复执行走 uk_matrix_no
--       冲突更新,不产生新行(首次执行后重跑,总数不变)。
--
-- ⚠️ 执行后必须失效矩阵缓存,否则运行时仍读旧缓存:
--      DEL ccr:cfg:matrix:effective        (Redis)
--    或走系统内「流程配置-矩阵维护」任意保存一次触发失效。
--    RateMatrixRouterImpl 只在缓存未命中时查库,直接改库不会触发失效。
--
-- 风险提示:在途单若发生审批调价重算,会按新边界计算(用户已确认接受)。
-- 回滚:见脚本尾部注释(依赖第 1 段生成的备份表)。
-- ============================================================

USE `ccr_rate`;

-- ============================================================
-- 第 0 段:执行前核对(只读,不改任何数据)
--   先单独跑这一段,人眼确认输出符合预期,再执行第 1 段及之后。
--   任一项对不上请停手核对,不要把本段与后续段落一起提交。
-- ============================================================

-- ① 现有 12 行现状。期望:恰好 12 行、del_flag='0'、customer_type 为 NULL(尚未拆国企/非国企)。
--    少于 12 行 → 生产的 matrix_no 与本脚本不一致,停手;
--    customer_type 已非 NULL → 本改动此前已执行过(或被人工调过),需先确认再决定是否继续。
SELECT `id`,`matrix_no`,`customer_type`,`new_or_existing`,`term_tier`,`start_node_code`,
       `dept_code`,`boundary_type`,`boundary_min_rate`,`boundary_bp`,`bp_sign`,`lpr_term`,
       `priority`,`status`,`del_flag`
FROM `ccr_rate_matrix`
WHERE `matrix_no` IN (
  'M-PUB-EX-LT1000-GM','M-PUB-EX-LT1000-VP','M-PUB-EX-LT1000-GROUP',
  'M-PUB-NEW-LT1000-1Y-GM','M-PUB-NEW-LT1000-1Y-VP','M-PUB-NEW-LT1000-1Y-GROUP',
  'M-PUB-NEW-LT1000-3Y-GM','M-PUB-NEW-LT1000-3Y-VP','M-PUB-NEW-LT1000-3Y-GROUP',
  'M-PUB-NEW-LT1000-5Y-GM','M-PUB-NEW-LT1000-5Y-VP','M-PUB-NEW-LT1000-5Y-GROUP')
ORDER BY `id`;

-- ② 对公 <1000万 档当前行数(期望 rows_=12)与全表最大 id(记下来,下面 INSERT 从这里往后接)
SELECT COUNT(*) AS `rows_` FROM `ccr_rate_matrix`
WHERE `del_flag`='0' AND `business_big_type`='LOAN_PUBLIC' AND `amount_tier`='LT_1000';
SELECT IFNULL(MAX(`id`),0) AS `max_id` FROM `ccr_rate_matrix`;

-- ③ 本次要新增的 SOE 行是否已存在(期望 0;若为 12 说明此前已执行过,重跑无害)
SELECT COUNT(*) AS `soe_rows_` FROM `ccr_rate_matrix`
WHERE `matrix_no` LIKE 'M-PUB-%-LT1000-SOE-%' AND `business_big_type`='LOAN_PUBLIC';

-- ④ LPR 生效值(仅用于核对上方注释里的百分比文案,不影响判定逻辑)
SELECT `version_code`,`lpr_1y`,`lpr_5y`,`effective_from`,`effective_to`
FROM `ccr_lpr_version` WHERE `del_flag`='0' AND `status`='EFFECTIVE'
ORDER BY `effective_from` DESC LIMIT 3;

-- ============================================================
-- 第 1 段:备份(首次执行生成改前快照;重复执行不会覆盖已生成的备份)
--   回滚依赖此表,请勿在确认投产稳定前删除。
-- ============================================================
CREATE TABLE IF NOT EXISTS `ccr_rate_matrix_bak_20260916` AS SELECT * FROM `ccr_rate_matrix`;

-- ============================================================
-- 第 2 段:现有 12 行 → NON_SOE(边界照抄 GE_1000_LT_5000 非国企行)
--   WHERE 另加 business_big_type / amount_tier 限定,防止 matrix_no 万一撞到别档位。
-- ============================================================

-- EXISTING 3 行(dept_code 保留零售金融部 3202233991——本次唯一例外项)
UPDATE `ccr_rate_matrix` SET
  `customer_type`='NON_SOE', `boundary_type`='SPREAD', `boundary_min_rate`=3.000000,
  `boundary_bp`=20, `bp_sign`=NULL, `lpr_term`=NULL,
  `remark`='对公存量<1000万非国企:零售金融部部门总经理 降幅0-20BP且不低于3.0%', `update_time`=NOW()
WHERE `matrix_no`='M-PUB-EX-LT1000-GM' AND `del_flag`='0'
  AND `business_big_type`='LOAN_PUBLIC' AND `amount_tier`='LT_1000';

UPDATE `ccr_rate_matrix` SET
  `customer_type`='NON_SOE', `boundary_type`='RATE', `boundary_min_rate`=3.000000,
  `boundary_bp`=NULL, `bp_sign`=NULL, `lpr_term`=NULL,
  `remark`='对公存量<1000万非国企:零售金融部分管行领导 降幅>20BP且不低于3.0%', `update_time`=NOW()
WHERE `matrix_no`='M-PUB-EX-LT1000-VP' AND `del_flag`='0'
  AND `business_big_type`='LOAN_PUBLIC' AND `amount_tier`='LT_1000';

UPDATE `ccr_rate_matrix` SET
  `customer_type`='NON_SOE', `boundary_type`='RATE', `boundary_min_rate`=3.000000,
  `boundary_bp`=NULL, `bp_sign`=NULL, `lpr_term`=NULL,
  `remark`='对公存量<1000万非国企:利率低于3.0%上会(≥4票)', `update_time`=NOW()
WHERE `matrix_no`='M-PUB-EX-LT1000-GROUP' AND `del_flag`='0'
  AND `business_big_type`='LOAN_PUBLIC' AND `amount_tier`='LT_1000';

-- NEW 9 行:一年期/三年期(取 LPR-1Y)
UPDATE `ccr_rate_matrix` SET
  `customer_type`='NON_SOE', `boundary_type`='RATE', `boundary_min_rate`=NULL,
  `boundary_bp`=40, `bp_sign`='+', `lpr_term`='1Y',
  `remark`='对公新增<1000万非国企一年期:授信评审部部门总经理 ≥LPR+40BP(3.4%)', `update_time`=NOW()
WHERE `matrix_no`='M-PUB-NEW-LT1000-1Y-GM' AND `del_flag`='0'
  AND `business_big_type`='LOAN_PUBLIC' AND `amount_tier`='LT_1000';

UPDATE `ccr_rate_matrix` SET
  `customer_type`='NON_SOE', `boundary_type`='RATE', `boundary_min_rate`=NULL,
  `boundary_bp`=20, `bp_sign`='+', `lpr_term`='1Y',
  `remark`='对公新增<1000万非国企一年期:授信评审部分管行领导 ≥LPR+20BP(3.2%)', `update_time`=NOW()
WHERE `matrix_no`='M-PUB-NEW-LT1000-1Y-VP' AND `del_flag`='0'
  AND `business_big_type`='LOAN_PUBLIC' AND `amount_tier`='LT_1000';

UPDATE `ccr_rate_matrix` SET
  `customer_type`='NON_SOE', `boundary_type`='RATE', `boundary_min_rate`=NULL,
  `boundary_bp`=0, `bp_sign`='+', `lpr_term`='1Y',
  `remark`='对公新增<1000万非国企一年期:<LPR(3.0%)上会(≥4票)', `update_time`=NOW()
WHERE `matrix_no`='M-PUB-NEW-LT1000-1Y-GROUP' AND `del_flag`='0'
  AND `business_big_type`='LOAN_PUBLIC' AND `amount_tier`='LT_1000';

UPDATE `ccr_rate_matrix` SET
  `customer_type`='NON_SOE', `boundary_type`='RATE', `boundary_min_rate`=NULL,
  `boundary_bp`=40, `bp_sign`='+', `lpr_term`='1Y',
  `remark`='对公新增<1000万非国企三年期:授信评审部部门总经理 ≥LPR+40BP(3.4%)', `update_time`=NOW()
WHERE `matrix_no`='M-PUB-NEW-LT1000-3Y-GM' AND `del_flag`='0'
  AND `business_big_type`='LOAN_PUBLIC' AND `amount_tier`='LT_1000';

UPDATE `ccr_rate_matrix` SET
  `customer_type`='NON_SOE', `boundary_type`='RATE', `boundary_min_rate`=NULL,
  `boundary_bp`=20, `bp_sign`='+', `lpr_term`='1Y',
  `remark`='对公新增<1000万非国企三年期:授信评审部分管行领导 ≥LPR+20BP(3.2%)', `update_time`=NOW()
WHERE `matrix_no`='M-PUB-NEW-LT1000-3Y-VP' AND `del_flag`='0'
  AND `business_big_type`='LOAN_PUBLIC' AND `amount_tier`='LT_1000';

UPDATE `ccr_rate_matrix` SET
  `customer_type`='NON_SOE', `boundary_type`='RATE', `boundary_min_rate`=NULL,
  `boundary_bp`=0, `bp_sign`='+', `lpr_term`='1Y',
  `remark`='对公新增<1000万非国企三年期:<LPR(3.0%)上会(≥4票)', `update_time`=NOW()
WHERE `matrix_no`='M-PUB-NEW-LT1000-3Y-GROUP' AND `del_flag`='0'
  AND `business_big_type`='LOAN_PUBLIC' AND `amount_tier`='LT_1000';

-- NEW 9 行:五年期以上(取 LPR-5Y)
UPDATE `ccr_rate_matrix` SET
  `customer_type`='NON_SOE', `boundary_type`='RATE', `boundary_min_rate`=NULL,
  `boundary_bp`=10, `bp_sign`='+', `lpr_term`='5Y',
  `remark`='对公新增<1000万非国企五年期:授信评审部部门总经理 ≥LPR5Y+10BP(3.6%)', `update_time`=NOW()
WHERE `matrix_no`='M-PUB-NEW-LT1000-5Y-GM' AND `del_flag`='0'
  AND `business_big_type`='LOAN_PUBLIC' AND `amount_tier`='LT_1000';

UPDATE `ccr_rate_matrix` SET
  `customer_type`='NON_SOE', `boundary_type`='RATE', `boundary_min_rate`=NULL,
  `boundary_bp`=10, `bp_sign`='-', `lpr_term`='5Y',
  `remark`='对公新增<1000万非国企五年期:授信评审部分管行领导 ≥LPR5Y-10BP(3.4%)', `update_time`=NOW()
WHERE `matrix_no`='M-PUB-NEW-LT1000-5Y-VP' AND `del_flag`='0'
  AND `business_big_type`='LOAN_PUBLIC' AND `amount_tier`='LT_1000';

UPDATE `ccr_rate_matrix` SET
  `customer_type`='NON_SOE', `boundary_type`='RATE', `boundary_min_rate`=NULL,
  `boundary_bp`=30, `bp_sign`='-', `lpr_term`='5Y',
  `remark`='对公新增<1000万非国企五年期:<LPR5Y-30BP(3.2%)上会(≥4票)', `update_time`=NOW()
WHERE `matrix_no`='M-PUB-NEW-LT1000-5Y-GROUP' AND `del_flag`='0'
  AND `business_big_type`='LOAN_PUBLIC' AND `amount_tier`='LT_1000';

-- ============================================================
-- 第 3 段:新增 12 行 SOE(国企)变体
--   id 不写死:由当前 MAX(id) 自适应往后排,避免生产 id 已被占用而主键冲突。
--   重复执行时 matrix_no 唯一键冲突 → 走 ON DUPLICATE KEY UPDATE,id 列不更新,不产生新行。
-- ============================================================
SET @base_id := (SELECT IFNULL(MAX(`id`),0) FROM `ccr_rate_matrix`);

INSERT INTO `ccr_rate_matrix`
  (`id`,`tenant_id`,`business_no`,`org_id`,`status`,`version_no`,`matrix_no`,
   `business_big_type`,`new_or_existing`,`customer_type`,`product_code`,`amount_tier`,`term_tier`,
   `guarantee_type`,`start_node_code`,`dept_code`,`boundary_type`,`boundary_min_rate`,`boundary_bp`,
   `bp_sign`,`lpr_term`,`priority`,`effective_from`,`effective_to`,`create_by`,`publish_by`,`review_by`,
   `publish_time`,`remark`)
VALUES
  -- 存量:降幅0-30BP且不低于3.0%;部门=零售金融部 3202233991(本次唯一例外项)
  (@base_id+1,'000000','MATRIX20260916001',1001,'EFFECTIVE',1,'M-PUB-EX-LT1000-SOE-GM',
   'LOAN_PUBLIC','EXISTING','SOE',NULL,'LT_1000',NULL,
   NULL,'DEPT_GENERAL_MANAGER','3202233991','SPREAD',3.000000,30,
   NULL,NULL,2,'2026-08-01 00:00:00',NULL,1000,1000,1000,
   NOW(),'对公存量<1000万国企:零售金融部部门总经理 降幅0-30BP且不低于3.0%'),

  (@base_id+2,'000000','MATRIX20260916002',1001,'EFFECTIVE',1,'M-PUB-EX-LT1000-SOE-VP',
   'LOAN_PUBLIC','EXISTING','SOE',NULL,'LT_1000',NULL,
   NULL,'VICE_PRESIDENT','3202233991','RATE',3.000000,NULL,
   NULL,NULL,3,'2026-08-01 00:00:00',NULL,1000,1000,1000,
   NOW(),'对公存量<1000万国企:零售金融部分管行领导 降幅>30BP且不低于3.0%'),

  (@base_id+3,'000000','MATRIX20260916003',1001,'EFFECTIVE',1,'M-PUB-EX-LT1000-SOE-GROUP',
   'LOAN_PUBLIC','EXISTING','SOE',NULL,'LT_1000',NULL,
   NULL,'SIX_PEOPLE_GROUP',NULL,'RATE',3.000000,NULL,
   NULL,NULL,4,'2026-08-01 00:00:00',NULL,1000,1000,1000,
   NOW(),'对公存量<1000万国企:利率低于3.0%上会(≥4票)'),

  -- 新增一年期
  (@base_id+4,'000000','MATRIX20260916004',1001,'EFFECTIVE',1,'M-PUB-NEW-LT1000-SOE-1Y-GM',
   'LOAN_PUBLIC','NEW','SOE',NULL,'LT_1000','1Y',
   NULL,'DEPT_GENERAL_MANAGER','3202233943','RATE',NULL,0,
   '+','1Y',2,'2026-08-01 00:00:00',NULL,1000,1000,1000,
   NOW(),'对公新增<1000万国企一年期:授信评审部部门总经理 ≥LPR(3.0%)'),

  (@base_id+5,'000000','MATRIX20260916005',1001,'EFFECTIVE',1,'M-PUB-NEW-LT1000-SOE-1Y-VP',
   'LOAN_PUBLIC','NEW','SOE',NULL,'LT_1000','1Y',
   NULL,'VICE_PRESIDENT','3202233943','RATE',NULL,10,
   '-','1Y',3,'2026-08-01 00:00:00',NULL,1000,1000,1000,
   NOW(),'对公新增<1000万国企一年期:授信评审部分管行领导 ≥LPR-10BP(2.9%)'),

  (@base_id+6,'000000','MATRIX20260916006',1001,'EFFECTIVE',1,'M-PUB-NEW-LT1000-SOE-1Y-GROUP',
   'LOAN_PUBLIC','NEW','SOE',NULL,'LT_1000','1Y',
   NULL,'SIX_PEOPLE_GROUP',NULL,'RATE',NULL,20,
   '-','1Y',4,'2026-08-01 00:00:00',NULL,1000,1000,1000,
   NOW(),'对公新增<1000万国企一年期:<LPR-20BP(2.8%)上会(≥4票)'),

  -- 新增三年期
  (@base_id+7,'000000','MATRIX20260916007',1001,'EFFECTIVE',1,'M-PUB-NEW-LT1000-SOE-3Y-GM',
   'LOAN_PUBLIC','NEW','SOE',NULL,'LT_1000','3Y',
   NULL,'DEPT_GENERAL_MANAGER','3202233943','RATE',NULL,0,
   '+','1Y',2,'2026-08-01 00:00:00',NULL,1000,1000,1000,
   NOW(),'对公新增<1000万国企三年期:授信评审部部门总经理 ≥LPR(3.0%)'),

  (@base_id+8,'000000','MATRIX20260916008',1001,'EFFECTIVE',1,'M-PUB-NEW-LT1000-SOE-3Y-VP',
   'LOAN_PUBLIC','NEW','SOE',NULL,'LT_1000','3Y',
   NULL,'VICE_PRESIDENT','3202233943','RATE',NULL,10,
   '-','1Y',3,'2026-08-01 00:00:00',NULL,1000,1000,1000,
   NOW(),'对公新增<1000万国企三年期:授信评审部分管行领导 ≥LPR-10BP(2.9%)'),

  (@base_id+9,'000000','MATRIX20260916009',1001,'EFFECTIVE',1,'M-PUB-NEW-LT1000-SOE-3Y-GROUP',
   'LOAN_PUBLIC','NEW','SOE',NULL,'LT_1000','3Y',
   NULL,'SIX_PEOPLE_GROUP',NULL,'RATE',NULL,20,
   '-','1Y',4,'2026-08-01 00:00:00',NULL,1000,1000,1000,
   NOW(),'对公新增<1000万国企三年期:<LPR-20BP(2.8%)上会(≥4票)'),

  -- 新增五年期以上
  (@base_id+10,'000000','MATRIX20260916010',1001,'EFFECTIVE',1,'M-PUB-NEW-LT1000-SOE-5Y-GM',
   'LOAN_PUBLIC','NEW','SOE',NULL,'LT_1000','5Y',
   NULL,'DEPT_GENERAL_MANAGER','3202233943','RATE',NULL,10,
   '-','5Y',2,'2026-08-01 00:00:00',NULL,1000,1000,1000,
   NOW(),'对公新增<1000万国企五年期:授信评审部部门总经理 ≥LPR5Y-10BP(3.4%)'),

  (@base_id+11,'000000','MATRIX20260916011',1001,'EFFECTIVE',1,'M-PUB-NEW-LT1000-SOE-5Y-VP',
   'LOAN_PUBLIC','NEW','SOE',NULL,'LT_1000','5Y',
   NULL,'VICE_PRESIDENT','3202233943','RATE',NULL,20,
   '-','5Y',3,'2026-08-01 00:00:00',NULL,1000,1000,1000,
   NOW(),'对公新增<1000万国企五年期:授信评审部分管行领导 ≥LPR5Y-20BP(3.3%)'),

  (@base_id+12,'000000','MATRIX20260916012',1001,'EFFECTIVE',1,'M-PUB-NEW-LT1000-SOE-5Y-GROUP',
   'LOAN_PUBLIC','NEW','SOE',NULL,'LT_1000','5Y',
   NULL,'SIX_PEOPLE_GROUP',NULL,'RATE',NULL,50,
   '-','5Y',4,'2026-08-01 00:00:00',NULL,1000,1000,1000,
   NOW(),'对公新增<1000万国企五年期:<LPR5Y-50BP(3.0%)上会(≥4票)')
ON DUPLICATE KEY UPDATE
  `customer_type`=VALUES(`customer_type`), `amount_tier`=VALUES(`amount_tier`),
  `term_tier`=VALUES(`term_tier`), `start_node_code`=VALUES(`start_node_code`),
  `dept_code`=VALUES(`dept_code`), `boundary_type`=VALUES(`boundary_type`),
  `boundary_min_rate`=VALUES(`boundary_min_rate`), `boundary_bp`=VALUES(`boundary_bp`),
  `bp_sign`=VALUES(`bp_sign`), `lpr_term`=VALUES(`lpr_term`), `priority`=VALUES(`priority`),
  `remark`=VALUES(`remark`), `update_time`=NOW();

-- ============================================================
-- 第 4 段:执行后自检(必须全绿)
-- ============================================================

-- ① 对公 LT_1000 应为 24 行:NON_SOE 12(EXISTING 3 + NEW 9) + SOE 12(EXISTING 3 + NEW 9)
SELECT `customer_type`, `new_or_existing`, COUNT(*) AS `rows_`
FROM `ccr_rate_matrix`
WHERE `del_flag`='0' AND `business_big_type`='LOAN_PUBLIC' AND `amount_tier`='LT_1000'
GROUP BY `customer_type`, `new_or_existing`
ORDER BY `customer_type`, `new_or_existing`;

-- ② 同 (new_or_existing, customer_type, term_tier, priority) 不得重复。
--    重复会在路由时抛 RULE_MULTI_MATCH,本查询须返回 0 行。
SELECT `new_or_existing`, `customer_type`, IFNULL(`term_tier`,'-') AS `term_tier`, `priority`, COUNT(*) AS `cnt_`
FROM `ccr_rate_matrix`
WHERE `del_flag`='0' AND `business_big_type`='LOAN_PUBLIC' AND `amount_tier`='LT_1000'
GROUP BY `new_or_existing`, `customer_type`, `term_tier`, `priority`
HAVING `cnt_` > 1;

-- ③ 与参照档 GE_1000_LT_5000 逐字段比对:除 dept_code 外应零差异(EXISTING 档部门按设计不同)。
SELECT m.`new_or_existing`, m.`customer_type`, IFNULL(m.`term_tier`,'-') AS `term_tier`, m.`priority`,
       CONCAT(m.`boundary_type`,'/',IFNULL(m.`boundary_min_rate`,'-'),'/',IFNULL(m.`boundary_bp`,'-'),
              '/',IFNULL(m.`bp_sign`,'-'),'/',IFNULL(m.`lpr_term`,'-')) AS `LT_1000_口径`,
       CONCAT(r.`boundary_type`,'/',IFNULL(r.`boundary_min_rate`,'-'),'/',IFNULL(r.`boundary_bp`,'-'),
              '/',IFNULL(r.`bp_sign`,'-'),'/',IFNULL(r.`lpr_term`,'-')) AS `GE_1000_LT_5000_口径`,
       m.`dept_code` AS `LT1000_dept`, r.`dept_code` AS `参照档_dept`
FROM `ccr_rate_matrix` m
JOIN `ccr_rate_matrix` r
  ON r.`del_flag`='0' AND r.`business_big_type`='LOAN_PUBLIC' AND r.`amount_tier`='GE_1000_LT_5000'
 AND r.`new_or_existing`=m.`new_or_existing` AND r.`customer_type`=m.`customer_type`
 AND IFNULL(r.`term_tier`,'-')=IFNULL(m.`term_tier`,'-') AND r.`priority`=m.`priority`
WHERE m.`del_flag`='0' AND m.`business_big_type`='LOAN_PUBLIC' AND m.`amount_tier`='LT_1000'
ORDER BY m.`new_or_existing`, m.`customer_type`, m.`priority`;

-- ============================================================
-- 第 5 段:回滚(仅在需要回退本次改动时执行;上方三段自检通过则无需执行)
--   依赖第 1 段生成的备份表 ccr_rate_matrix_bak_20260916。
--   执行后同样需要 DEL ccr:cfg:matrix:effective。
-- ============================================================
-- -- ① 删除本次新增的 12 行 SOE
-- DELETE FROM `ccr_rate_matrix`
-- WHERE `business_big_type`='LOAN_PUBLIC' AND `amount_tier`='LT_1000'
--   AND `matrix_no` LIKE 'M-PUB-%-LT1000-SOE-%';
--
-- -- ② 原有 12 行按备份表恢复为改动前的值
-- UPDATE `ccr_rate_matrix` m
--   JOIN `ccr_rate_matrix_bak_20260916` b ON b.`matrix_no` = m.`matrix_no`
-- SET m.`customer_type`=b.`customer_type`, m.`boundary_type`=b.`boundary_type`,
--     m.`boundary_min_rate`=b.`boundary_min_rate`, m.`boundary_bp`=b.`boundary_bp`,
--     m.`bp_sign`=b.`bp_sign`, m.`lpr_term`=b.`lpr_term`, m.`remark`=b.`remark`,
--     m.`update_time`=NOW()
-- WHERE m.`del_flag`='0'
--   AND m.`business_big_type`='LOAN_PUBLIC' AND m.`amount_tier`='LT_1000';
--
-- -- ③ 清缓存: DEL ccr:cfg:matrix:effective
-- -- ④ 复核:回到第 4 段 ① 的查询,应恢复为 NON+SOE 各 12 行之前的状态
-- --       (即 customer_type 为 NULL 的 12 行、SOE 计数为 0)
