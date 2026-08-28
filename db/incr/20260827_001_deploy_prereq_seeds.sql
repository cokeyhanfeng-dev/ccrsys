-- ============================================================
-- 20260827_001 生产部署前置配置补全(幂等)
--
-- 背景:生产 8-26 增量清单仅交付 026_002(report_date),以下三类
--   前置配置未随增量交付,而 8-27 新 jar(80b88e4)及 8-26 基线 jar
--   (4513960)均含 guardNodeAssignee 严格校验 —— 缺任一配置将导致
--   GM/VP 节点解析空、流程报「申请缺少部门归属配置」或矩阵误判。
--
-- 组成(全部幂等,可重复执行;先查后补,已有则跳过):
--   ① 贷款支行行长取消利率终审权:删除 9 行 BRANCH_MANAGER 矩阵行
--      (与 db/incr/20260826_001_remove_rate_matrix_bm.sql 同效)
--   ② 部门总经理节点:旧格式纯 org_code → 冒号语法 dept_code:dept_gm
--      (2026-08-27 生产事故根因;UPDATE 幂等)
--   ③ 分管行领导账号 + 部门-分管行长映射(ccr_dept_vp)补种
--      (3 位 VP 账号 2092000000000002001-3 + 3 条映射,幂等)
--
-- 执行:docker exec -i ccr-prod-mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" ccr_rate' < 本文件
-- 前置检查 SQL 见脚本尾部(部署前先自查,明确缺哪些)
-- 敏感:仅 DDL+初始化种子,无真实业务数据,可入库管理
-- ============================================================

-- ---------- ① 贷款支行行长取消利率终审权(删 9 行 BM) ----------
-- 支行行长必经首节点由后端 FIRST_NODE("BRANCH_MANAGER")硬编码保证,删行不影响必经;
-- DELETE 不存在即 0 行,幂等。
DELETE FROM ccr_rate_matrix
WHERE matrix_no IN (
  'M-PUB-NEW-LT-NSOE-1Y-BM',
  'M-PUB-NEW-LT-NSOE-3Y-BM',
  'M-PUB-NEW-LT-NSOE-5Y-BM',
  'M-PUB-NEW-LT1000-1Y-BM',
  'M-PUB-NEW-LT1000-3Y-BM',
  'M-PUB-NEW-LT1000-5Y-BM',
  'M-PER-NEW-1Y-BM',
  'M-PER-NEW-3Y-BM',
  'M-PER-NEW-5Y-BM'
);

-- ---------- ② 部门总经理节点:旧格式 org_code → 冒号语法 dept_code:dept_gm ----------
-- 生产 8-27 事故根因:新 jar 要求 assignee_code='dept_code:dept_gm' 冒号语法;
-- 旧格式纯 org_code('3202233943')走「申请人机构属于该部门组织树」分支,申请人是支行→解析空→拒绝。
-- UPDATE 只处理含旧格式值的行(不含冒号),已冒号或已删配置的行不受影响,幂等。
UPDATE ccr_node_assignee
SET assignee_code = CONCAT(assignee_code, ':dept_gm'),
    update_time   = NOW()
WHERE node_code = 'DEPT_GENERAL_MANAGER'
  AND assignee_type = 'DEPT'
  AND assignee_code IS NOT NULL
  AND assignee_code NOT LIKE '%:%';

-- ---------- ③ 分管行领导账号补种(幂等) ----------
-- 02300744 陈开成→公司金融部(3202233912)/01500064 史志明→授信评审部(3202233943)/02300483 侯允杰→零售金融(3202233991);
-- 登录名=工号,密码=统一初始密码 Yxnsh@1a3s(共享 BCrypt,首登强制改密);org_id 为部门 id;
-- ON DUPLICATE KEY(uk_username)重复执行仅刷新昵称/部门/角色,不重置密码。
INSERT INTO `ccr_sys_user` (`id`,`username`,`password`,`nick_name`,`role_code`,`org_id`,`phone`,`status`) VALUES
  (2092000000000002001,'02300744','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','陈开成','vice_president',1040,'13800000241','ENABLE'),
  (2092000000000002002,'01500064','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','史志明','vice_president',1050,'13800000242','ENABLE'),
  (2092000000000002003,'02300483','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','侯允杰','vice_president',1056,'13800000243','ENABLE')
ON DUPLICATE KEY UPDATE nick_name=VALUES(nick_name), org_id=VALUES(org_id), role_code=VALUES(role_code), status='ENABLE';

-- ---------- ③b 部门-分管行长映射表补建+补种(幂等) ----------
CREATE TABLE IF NOT EXISTS `ccr_dept_vp` (
  `id`             BIGINT       NOT NULL COMMENT '雪花主键',
  `tenant_id`      VARCHAR(20)  NOT NULL DEFAULT '000000' COMMENT '租户标识',
  `dept_code`      VARCHAR(64)  NOT NULL COMMENT '部门归属编码(机构org_code,对齐 ccr_rate_matrix.dept_code)',
  `vp_user_id`     BIGINT       NOT NULL COMMENT '分管行领导用户id(ccr_sys_user.id,复用 vice_president 角色码)',
  `status`         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'EFFECTIVE/INACTIVE',
  `valid_from`     DATETIME     NULL COMMENT '生效时间(空=不限)',
  `valid_to`       DATETIME     NULL COMMENT '失效时间(空=不限)',
  `version_no`     INT          NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  `del_flag`       CHAR(1)      NOT NULL DEFAULT '0' COMMENT '逻辑删除0否1是',
  `create_by`      VARCHAR(64)  NULL COMMENT '创建人',
  `create_time`    DATETIME     NULL COMMENT '创建时间',
  `update_by`      VARCHAR(64)  NULL COMMENT '更新人',
  `update_time`    DATETIME     NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_dept_code` (`dept_code`),
  KEY `idx_vp_user` (`vp_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='部门-分管行领导映射(§D16a;一人可分管多部门,纯配置)';

INSERT INTO `ccr_dept_vp` (`id`,`tenant_id`,`dept_code`,`vp_user_id`,`status`,`version_no`,`create_by`,`create_time`,`del_flag`)
SELECT 2092000000000000001,'000000','3202233912',2092000000000002001,'ACTIVE',1,'1004',NOW(),'0'
WHERE NOT EXISTS (SELECT 1 FROM `ccr_dept_vp` WHERE dept_code='3202233912' AND del_flag='0');
INSERT INTO `ccr_dept_vp` (`id`,`tenant_id`,`dept_code`,`vp_user_id`,`status`,`version_no`,`create_by`,`create_time`,`del_flag`)
SELECT 2092000000000000002,'000000','3202233943',2092000000000002002,'ACTIVE',1,'1004',NOW(),'0'
WHERE NOT EXISTS (SELECT 1 FROM `ccr_dept_vp` WHERE dept_code='3202233943' AND del_flag='0');
INSERT INTO `ccr_dept_vp` (`id`,`tenant_id`,`dept_code`,`vp_user_id`,`status`,`version_no`,`create_by`,`create_time`,`del_flag`)
SELECT 2092000000000000003,'000000','3202233991',2092000000000002003,'ACTIVE',1,'1004',NOW(),'0'
WHERE NOT EXISTS (SELECT 1 FROM `ccr_dept_vp` WHERE dept_code='3202233991' AND del_flag='0');

-- ============================================================
-- 前置检查(部署前先自查,确认生产缺哪些;全部幂等,可重跑)
-- ============================================================
-- ① 矩阵 BM 行(期望 0):
--   SELECT COUNT(*) FROM ccr_rate_matrix WHERE matrix_no LIKE '%-BM';
-- ② GM 冒号配置(期望 3 行,assignee_code 均含冒号):
--   SELECT assignee_code FROM ccr_node_assignee
--     WHERE node_code='DEPT_GENERAL_MANAGER' AND assignee_type='DEPT' AND del_flag='0';
-- ③ VP 账号(期望 3 行):
--   SELECT id, username, nick_name FROM ccr_sys_user
--     WHERE role_code='vice_president' AND del_flag='0';
-- ③b ccr_dept_vp 映射(期望 3 行):
--   SELECT dept_code, vp_user_id FROM ccr_dept_vp WHERE del_flag='0';
-- ④ (本次 8 项变更无 SQL;若生产未执行 026_002 report_date,需补执行)
--   20260826_002_credit_summary_report_date.sql(幂等,可重复执行)
-- ============================================================
