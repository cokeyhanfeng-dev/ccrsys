-- ============================================================
-- 纾困调息(特殊资产管理部,2026-09-24)
-- 需求:特殊资产部对困难客户做存量贷款利率调整,独立菜单「纾困调息」,
--      客户经理发起 → 支行行长 → 特殊资产部总经理 → 分管行长 → 六人小组 → 总行行长;
--      其中【申请利率 ≥4.0% 的止于特殊资产部总经理终审】,【<4.0% 走完整链到总行行长】。
--
-- 实现口径(不改流程引擎一行代码,纯矩阵配置 + 菜单):
--   1) 产品码 LOAN_SA(纾困调息)作为特资专用标识,由申请页内定写入分项
--      ccr_pricing_item.product_code,经 buildRouteInput 流入 MatrixRouteInput.productCode
--      (ApplicationSubmitServiceImpl.buildRouteInput:1929),用于矩阵 match 的产品维度区分。
--      客户经理界面不选产品,该码仅用于路由匹配与决议书「产品」列展示。
--   2) 三行矩阵 customer_type/product_code 之外维度全留空 = 通配,
--      故「不限客户、不涉及金额、不分期限」(match 对空维度跳过校验)。
--   3) 链路拼装依赖 buildChain:命中行的 start_node_code 按 priority 升序累加进链路。
--      故 VICE_PRESIDENT 行虽然永不终审(≥4.0% 已被 GM 行截走),
--      仍会被写入链路,从而实现「分管行长必经」——与现有贷款链同一套机制。
--   3a) 【三行优先级必须 < 2,取 -3/-2/-1】普通对公存量行优先级最小=2,且 product_code 留空(通配),
--      特资申请同样命中这些通配行;而普通行 boundary_min_rate 为空即「权限内即终审(D3)」
--      (calcRoute:180-183),会在特资行之前抢先截断。收窄为 product_code 专属行后,
--      低利率(<4.0%)走「循环无岗位可终审 → 上会兜底」分支,兜底行即本表第三行。
--      配套代码:RateMatrixRouterImpl.calcRoute 对命中 product_code 专属行的场景收窄遍历范围,
--      使通配行不再参与「找终审岗位」;现网贷款矩阵除本脚本外无 product_code 专属行,
--      普通贷款的 loopRows 为空、回退全量遍历,行为不变(已回归验证)。
--   4) dept_code 三行统一填特资部 org_code 3202233915:
--      部门总经理按「dept_code(机构org_code) → 该机构下 dept_gm 角色用户」动态解析
--      (见 11_system_increment.sql:275),特资部总经理袁科威(02300233)已在该岗,无需另配 ccr_node_assignee;
--      分管行长按 ccr_dept_vp 按 dept_code 精确映射,故本脚本需补一行。
--
-- 幂等写法仿 03f_node_assignee.sql,可重复执行。
-- ============================================================

USE `ccr_rate`;

-- ---------- 1. 权限矩阵:纾困调息三行 ----------
-- 优先级 -3:DEPT_GENERAL_MANAGER 边界 4.0 —— 申请利率 ≥4.0% 时命中,特资部总经理终审(链在此截断)
INSERT INTO `ccr_rate_matrix`
  (`id`,`tenant_id`,`business_no`,`org_id`,`status`,`version_no`,`create_by`,`create_time`,`del_flag`,
   `matrix_no`,`business_big_type`,`new_or_existing`,`customer_type`,`product_code`,`amount_tier`,`term_tier`,`guarantee_type`,
   `start_node_code`,`dept_code`,`boundary_type`,`boundary_min_rate`,`boundary_bp`,`bp_sign`,`lpr_term`,`priority`,
   `effective_from`,`effective_to`,`publish_by`,`review_by`,`publish_time`,`remark`)
SELECT 9911,'000000','MATRIX20260924001',1001,'EFFECTIVE',1,1000,NOW(),'0',
       'M-SA-EX-GM','LOAN_PUBLIC','EXISTING',NULL,'LOAN_SA',NULL,NULL,NULL,
       'DEPT_GENERAL_MANAGER','3202233915','RATE',4.000000,NULL,NULL,NULL,-3,
       '2026-09-24 00:00:00',NULL,1000,1000,NOW(),
       '纾困调息(存量困难客户):申请利率≥4.0%,特殊资产管理部总经理终审'
WHERE NOT EXISTS (SELECT 1 FROM `ccr_rate_matrix` WHERE matrix_no='M-SA-EX-GM');

-- 优先级 -2:VICE_PRESIDENT 边界同为 4.0 —— 该行【永不终审】:
-- 申请利率 ≥4.0% 在优先级 -3 即被特资部总经理行截走;<4.0% 时本行边界不命中(rate < 4.0)。
-- 存在的唯一目的是让 buildChain 把 VICE_PRESIDENT 写进链路 → 「分管行长」成为必经节点。
-- 分管行长人选由 ccr_dept_vp 按 dept_code 解析(见本脚本第 2 段)。
INSERT INTO `ccr_rate_matrix`
  (`id`,`tenant_id`,`business_no`,`org_id`,`status`,`version_no`,`create_by`,`create_time`,`del_flag`,
   `matrix_no`,`business_big_type`,`new_or_existing`,`customer_type`,`product_code`,`amount_tier`,`term_tier`,`guarantee_type`,
   `start_node_code`,`dept_code`,`boundary_type`,`boundary_min_rate`,`boundary_bp`,`bp_sign`,`lpr_term`,`priority`,
   `effective_from`,`effective_to`,`publish_by`,`review_by`,`publish_time`,`remark`)
SELECT 9912,'000000','MATRIX20260924002',1001,'EFFECTIVE',1,1000,NOW(),'0',
       'M-SA-EX-VP','LOAN_PUBLIC','EXISTING',NULL,'LOAN_SA',NULL,NULL,NULL,
       'VICE_PRESIDENT','3202233915','RATE',4.000000,NULL,NULL,NULL,-2,
       '2026-09-24 00:00:00',NULL,1000,1000,NOW(),
       '纾困调息:分管行长必经(仅写入链路,不终审;终审归特殊资产管理部总经理或六人小组)'
WHERE NOT EXISTS (SELECT 1 FROM `ccr_rate_matrix` WHERE matrix_no='M-SA-EX-VP');

-- 优先级 -1:SIX_PEOPLE_GROUP 兜底 —— 申请利率 <4.0% 时全部岗位边界不命中,上会六人小组(≥4票),
-- 终审=小组 → applyPresident 自动追加 PRESIDENT(总行行长),无需配置。
-- 注意:本行 start_node_code=SIX_PEOPLE_GROUP,在 calcRoute 循环内被 continue 跳过(上会兜底行最后处理),
-- 其 boundary_min_rate 不参与「找终审岗位」比较,值保持 4.0 仅为需求语义可读(与上会阈值一致)。
INSERT INTO `ccr_rate_matrix`
  (`id`,`tenant_id`,`business_no`,`org_id`,`status`,`version_no`,`create_by`,`create_time`,`del_flag`,
   `matrix_no`,`business_big_type`,`new_or_existing`,`customer_type`,`product_code`,`amount_tier`,`term_tier`,`guarantee_type`,
   `start_node_code`,`dept_code`,`boundary_type`,`boundary_min_rate`,`boundary_bp`,`bp_sign`,`lpr_term`,`priority`,
   `effective_from`,`effective_to`,`publish_by`,`review_by`,`publish_time`,`remark`)
SELECT 9913,'000000','MATRIX20260924003',1001,'EFFECTIVE',1,1000,NOW(),'0',
       'M-SA-EX-GROUP','LOAN_PUBLIC','EXISTING',NULL,'LOAN_SA',NULL,NULL,NULL,
       'SIX_PEOPLE_GROUP','3202233915','RATE',4.000000,NULL,NULL,NULL,-1,
       '2026-09-24 00:00:00',NULL,1000,1000,NOW(),
       '纾困调息:申请利率<4.0%,上会六人小组表决(≥4票)后报总行行长最终决议'
WHERE NOT EXISTS (SELECT 1 FROM `ccr_rate_matrix` WHERE matrix_no='M-SA-EX-GROUP');

-- 优先级校准(幂等):若目标库已执行过本脚本的早期版本(priority 10/20/99),
-- 上面的 INSERT ... WHERE NOT EXISTS 会整段跳过,旧值遗留导致特资申请被普通通配行抢先截断。
-- 故此处强制回写一次,保证重复执行到任何版本都能收敛到 -3/-2/-1。
UPDATE `ccr_rate_matrix` SET `priority` = -3 WHERE `matrix_no` = 'M-SA-EX-GM';
UPDATE `ccr_rate_matrix` SET `priority` = -2 WHERE `matrix_no` = 'M-SA-EX-VP';
UPDATE `ccr_rate_matrix` SET `priority` = -1 WHERE `matrix_no` = 'M-SA-EX-GROUP';

-- 备注改名校准(幂等,2026-09-24 用户拍板):业务统一改称「纾困调息」。
-- 上面三条 INSERT 带 WHERE NOT EXISTS,目标库若已建行则整段跳过、旧备注(「特资利率申请…」)会遗留,
-- 故与 priority 同法强制回写一次。
UPDATE `ccr_rate_matrix` SET `remark` = '纾困调息(存量困难客户):申请利率≥4.0%,特殊资产管理部总经理终审'
 WHERE `matrix_no` = 'M-SA-EX-GM';
UPDATE `ccr_rate_matrix` SET `remark` = '纾困调息:分管行长必经(仅写入链路,不终审;终审归特殊资产管理部总经理或六人小组)'
 WHERE `matrix_no` = 'M-SA-EX-VP';
UPDATE `ccr_rate_matrix` SET `remark` = '纾困调息:申请利率<4.0%,上会六人小组表决(≥4票)后报总行行长最终决议'
 WHERE `matrix_no` = 'M-SA-EX-GROUP';

-- ---------- 2. 部门分管行长映射:特殊资产管理部(3202233915) → 侯允杰 ----------
-- 现有仅 3 行(公司金融部→陈开成/授信评审部→史志明/零售金融部→侯允杰),特资部无映射,
-- 不补则 VICE_PRESIDENT 节点解析不到处理人、审批链路中断。
INSERT INTO `ccr_dept_vp`
  (`id`,`tenant_id`,`dept_code`,`vp_user_id`,`status`,`version_no`,`create_by`,`create_time`,`del_flag`)
SELECT 2092000000000000004,'000000','3202233915',2092000000000002003,'ACTIVE',1,'1004',NOW(),'0'
WHERE NOT EXISTS (SELECT 1 FROM `ccr_dept_vp` WHERE dept_code='3202233915' AND del_flag='0');

-- ---------- 3. 菜单:纾困调息(id=15,现有最大 14=决议书查询) ----------
-- 菜单名 2026-09-24 用户拍板由「特资利率申请」改为「纾困调息」;本 INSERT 的
-- ON DUPLICATE KEY UPDATE 已带 menu_name,故对目标库重复执行即完成改名,无需另写 UPDATE。
INSERT INTO `ccr_sys_menu`
  (`id`,`parent_id`,`menu_name`,`path`,`perms`,`sort_no`)
VALUES
  (15,0,'纾困调息','/special-asset','ccr:special-asset',15)
ON DUPLICATE KEY UPDATE
  menu_name=VALUES(menu_name), path=VALUES(path), perms=VALUES(perms), sort_no=VALUES(sort_no);

-- ---------- 4. 菜单授权:所有客户经理可发起(用户口径) + admin 全可见 ----------
UPDATE `ccr_sys_role` SET `menu_ids` = CONCAT(`menu_ids`, ',15')
WHERE `role_code` = 'customer_manager' AND FIND_IN_SET('15', `menu_ids`) = 0;

UPDATE `ccr_sys_role` SET `menu_ids` = CONCAT(`menu_ids`, ',15')
WHERE `role_code` = 'admin' AND FIND_IN_SET('15', `menu_ids`) = 0;

-- ============================================================
-- 执行后必做:清矩阵生效缓存(否则路由仍读旧矩阵)
--   redis-cli -p 16379 DEL ccr:cfg:matrix:effective
--   (或重启 backend 容器;缓存 key 见 CcrCacheUtil.KEY_MATRIX_EFFECTIVE)
--
-- 自查 SQL:
--   -- 三行矩阵(期望 3 行)
--   SELECT matrix_no,start_node_code,boundary_min_rate,priority,dept_code
--     FROM ccr_rate_matrix WHERE matrix_no LIKE 'M-SA-%' ORDER BY priority;
--   -- 特资部分管行长(期望 1 行,vp_user_id=2092000000000002003)
--   SELECT dept_code,vp_user_id FROM ccr_dept_vp WHERE dept_code='3202233915' AND del_flag='0';
--   -- 菜单与授权(期望 customer_manager/admin 的 menu_ids 均含 15)
--   SELECT id,menu_name,path FROM ccr_sys_menu WHERE id=15;
--   SELECT role_code,menu_ids FROM ccr_sys_role WHERE role_code IN ('customer_manager','admin');
--
-- 端到端验收:发起两笔特资申请(一笔 4.2%、一笔 3.8%),提交后查冻结链路
--   SELECT application_no,start_node_code,route_code,route_chain
--     FROM ccr_application WHERE business_type='LOAN' ORDER BY id DESC LIMIT 2;
--   期望:4.2% → BRANCH_MANAGER,DEPT_GENERAL_MANAGER
--         3.8% → BRANCH_MANAGER,DEPT_GENERAL_MANAGER,VICE_PRESIDENT,SIX_PEOPLE_GROUP,PRESIDENT
-- ============================================================
