-- 20260821_004 warm-flow 标准流程跳转关系补全(flow_skip)
-- 背景:生产「利率审批标准流程」(flow_code=rate_approval)审批到支行行长下一步报
--      NodeServiceImpl.lambda$getNextNode NPE。根因:flow_definition 节点表有 7 个节点
--      (start→支行行长→部门总经理→分管副行长→六人小组→总行行长→end),但跳转关系表
--      flow_skip 为空(旧版初始化只建节点没建跳转),warm-flow 跳转时查不到下一步→NPE。
-- 影响表:flow_skip(INSERT 6 条逐级上送 PASS 跳转)
-- 幂等:每条 INSERT 带 NOT EXISTS 保护,重复执行不会重复插入。
-- 兼容性:
--   ① id 用固定安全值(9000000000000000001~0006,均在 BIGINT 有符号范围内)。
--      ⚠️ 勿改用 UUID_SHORT():其返回值可为无符号 64 位超 BIGINT 上限,生产实测报
--         "Out of range value for column 'id'"。
--   ② definition_id 用子查询按 flow_code 自动取,无需手工替换。
--   ③ 不依赖会话变量,每段自包含,支持 mysql 客户端/Navicat/DBeaver。
-- 若生产 flow_code 不叫 rate_approval:把脚本内两处 'rate_approval' 换成实际值再执行。
-- 无 Redis key 清理(flow_skip 无缓存,warm-flow 每次跳转实时查库,插完即生效,无需重启后端)。
-- 执行命令:docker exec -i ccr-mysql mysql -uroot -proot123 --default-character-set=utf8mb4 < db/incr/20260821_004_flow_skip_standard_chain.sql

USE ccr_rate;

-- ① 诊断:确认流程定义存在(正常应返回一条 rate_approval)
SELECT id, flow_code, flow_name, version, is_publish
FROM flow_definition
WHERE flow_code = 'rate_approval';

-- ② 检查该流程当前跳转数(正常为 0;已非 0 则 ③ 会被 NOT EXISTS 全部跳过)
SELECT COUNT(*) AS skip_cnt
FROM flow_skip
WHERE definition_id = (SELECT id FROM flow_definition WHERE flow_code='rate_approval' AND is_publish=1 LIMIT 1);

-- ③ 插入 6 条逐级上送跳转(每条独立幂等,可重复执行)
INSERT INTO flow_skip
  (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, create_time, create_by, update_time, update_by, del_flag)
SELECT 9000000000000000001, d.id, 'start', 0, 'BRANCH_MANAGER', 1, '通过', 'PASS', NULL, NOW(), 'admin', NOW(), 'admin', '0'
FROM (SELECT id FROM flow_definition WHERE flow_code='rate_approval' AND is_publish=1 LIMIT 1) d
WHERE NOT EXISTS (SELECT 1 FROM flow_skip s WHERE s.definition_id = d.id AND s.now_node_code='start' AND s.next_node_code='BRANCH_MANAGER');

INSERT INTO flow_skip
  (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, create_time, create_by, update_time, update_by, del_flag)
SELECT 9000000000000000002, d.id, 'BRANCH_MANAGER', 1, 'DEPT_GENERAL_MANAGER', 1, '通过', 'PASS', NULL, NOW(), 'admin', NOW(), 'admin', '0'
FROM (SELECT id FROM flow_definition WHERE flow_code='rate_approval' AND is_publish=1 LIMIT 1) d
WHERE NOT EXISTS (SELECT 1 FROM flow_skip s WHERE s.definition_id = d.id AND s.now_node_code='BRANCH_MANAGER' AND s.next_node_code='DEPT_GENERAL_MANAGER');

INSERT INTO flow_skip
  (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, create_time, create_by, update_time, update_by, del_flag)
SELECT 9000000000000000003, d.id, 'DEPT_GENERAL_MANAGER', 1, 'VICE_PRESIDENT', 1, '通过', 'PASS', NULL, NOW(), 'admin', NOW(), 'admin', '0'
FROM (SELECT id FROM flow_definition WHERE flow_code='rate_approval' AND is_publish=1 LIMIT 1) d
WHERE NOT EXISTS (SELECT 1 FROM flow_skip s WHERE s.definition_id = d.id AND s.now_node_code='DEPT_GENERAL_MANAGER' AND s.next_node_code='VICE_PRESIDENT');

INSERT INTO flow_skip
  (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, create_time, create_by, update_time, update_by, del_flag)
SELECT 9000000000000000004, d.id, 'VICE_PRESIDENT', 1, 'SIX_PEOPLE_GROUP', 1, '通过', 'PASS', NULL, NOW(), 'admin', NOW(), 'admin', '0'
FROM (SELECT id FROM flow_definition WHERE flow_code='rate_approval' AND is_publish=1 LIMIT 1) d
WHERE NOT EXISTS (SELECT 1 FROM flow_skip s WHERE s.definition_id = d.id AND s.now_node_code='VICE_PRESIDENT' AND s.next_node_code='SIX_PEOPLE_GROUP');

INSERT INTO flow_skip
  (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, create_time, create_by, update_time, update_by, del_flag)
SELECT 9000000000000000005, d.id, 'SIX_PEOPLE_GROUP', 1, 'PRESIDENT', 1, '通过', 'PASS', NULL, NOW(), 'admin', NOW(), 'admin', '0'
FROM (SELECT id FROM flow_definition WHERE flow_code='rate_approval' AND is_publish=1 LIMIT 1) d
WHERE NOT EXISTS (SELECT 1 FROM flow_skip s WHERE s.definition_id = d.id AND s.now_node_code='SIX_PEOPLE_GROUP' AND s.next_node_code='PRESIDENT');

INSERT INTO flow_skip
  (id, definition_id, now_node_code, now_node_type, next_node_code, next_node_type, skip_name, skip_type, skip_condition, create_time, create_by, update_time, update_by, del_flag)
SELECT 9000000000000000006, d.id, 'PRESIDENT', 1, 'end', 2, '通过', 'PASS', NULL, NOW(), 'admin', NOW(), 'admin', '0'
FROM (SELECT id FROM flow_definition WHERE flow_code='rate_approval' AND is_publish=1 LIMIT 1) d
WHERE NOT EXISTS (SELECT 1 FROM flow_skip s WHERE s.definition_id = d.id AND s.now_node_code='PRESIDENT' AND s.next_node_code='end');

-- ④ 验证:应出现 6 行
SELECT now_node_code, next_node_code, skip_type
FROM flow_skip
WHERE definition_id = (SELECT id FROM flow_definition WHERE flow_code='rate_approval' AND is_publish=1 LIMIT 1)
ORDER BY id;

-- 无规则/矩阵/缓存类配置改动,无需清理 Redis key。
