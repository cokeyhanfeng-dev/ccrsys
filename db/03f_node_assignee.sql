-- ============================================================
-- 客户贡献度与利率决策系统 · 节点审批人配置 + 审计反查 + 导出记录
-- 依据:设计文档V1.0 §5.5.1、§10.3.19、§11.10、§11.11、§12.17
-- 口径:审批人可配置(按人/组/部门/角色四层解析,代理暂代+有效期);
--      审计反查与导出记录全程留痕
-- ============================================================

USE `ccr_rate`;

-- ---------- 节点审批人指派 ----------
CREATE TABLE IF NOT EXISTS `ccr_node_assignee` (
  `id`                  BIGINT       NOT NULL,
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000',
  `flow_key`            VARCHAR(64)  NULL COMMENT '流程定义key(Warm-Flow),空=适用该节点所有流程',
  `node_code`           VARCHAR(64)  NOT NULL COMMENT '节点编码:BRANCH_MANAGER/DEPT_GENERAL_MANAGER/VICE_PRESIDENT/SIX_PEOPLE_GROUP/PRESIDENT/SECRETARY',
  `assignee_type`       VARCHAR(20)  NOT NULL COMMENT 'PERSON按人/GROUP按组(角色集合,逗号分隔)/DEPT按机构/ROLE按角色兜底',
  `assignee_code`       VARCHAR(64)  NOT NULL COMMENT '工号/组编码/机构org_code/角色码',
  `relation`            VARCHAR(10)  NOT NULL DEFAULT 'OR' COMMENT 'AND需全员/OR任一(默认)',
  `delegate_to`         VARCHAR(64)  NULL COMMENT '代理人工号(暂代)',
  `delegate_valid_from` DATETIME     NULL COMMENT '代理有效期起,空=立即',
  `delegate_valid_to`   DATETIME     NULL COMMENT '代理有效期止,空=长期',
  `valid_from`          DATE         NULL COMMENT '配置有效期起,空=长期',
  `valid_to`            DATE         NULL COMMENT '配置有效期止,空=长期',
  `status`              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  `remark`              VARCHAR(255) NULL,
  `version_no`          INT          NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  `create_by`           BIGINT       NULL,
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       NULL,
  `update_time`         DATETIME     NULL,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_node_assignee` (`flow_key`,`node_code`,`assignee_type`,`assignee_code`,`valid_from`),
  KEY `idx_assignee_node` (`node_code`,`valid_from`,`valid_to`)
) ENGINE=InnoDB COMMENT='ccr_node_assignee 节点审批人指派(§5.5.1/§10.3.19)';

-- ---------- 审计操作日志 ----------
CREATE TABLE IF NOT EXISTS `ccr_audit_log` (
  `id`                  BIGINT       NOT NULL,
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000',
  `log_type`            VARCHAR(32)  NOT NULL COMMENT 'BALLOT_DETAIL票据反查/ASSIGNEE_CHANGE指派变更/DELEGATE代理设置',
  `biz_id`              VARCHAR(64)  NULL COMMENT '业务主键(批次id/指派id等)',
  `content`             VARCHAR(2000) NULL COMMENT '操作内容摘要',
  `operator_id`         BIGINT       NOT NULL COMMENT '操作人',
  `operator_name`       VARCHAR(64)  NULL COMMENT '操作人姓名',
  `operate_time`        DATETIME     NOT NULL COMMENT '操作时间',
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_audit_log` (`log_type`,`operate_time`)
) ENGINE=InnoDB COMMENT='ccr_audit_log 审计操作日志(§11.10/§15.3)';

-- ---------- 档案导出记录 ----------
CREATE TABLE IF NOT EXISTS `ccr_export_record` (
  `id`                  BIGINT       NOT NULL,
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000',
  `export_no`           VARCHAR(64)  NOT NULL COMMENT '导出单号(唯一)',
  `application_id`      BIGINT       NOT NULL COMMENT '申请主键',
  `export_type`         VARCHAR(32)  NOT NULL COMMENT '导出类型:ARCHIVE_XLSX历史档案',
  `file_name`           VARCHAR(255) NOT NULL COMMENT '导出文件名',
  `operator_id`         BIGINT       NOT NULL COMMENT '导出人',
  `operator_name`       VARCHAR(64)  NULL COMMENT '导出人姓名',
  `org_id`              BIGINT       NULL COMMENT '导出人机构',
  `export_time`         DATETIME     NOT NULL COMMENT '导出时间',
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_export_no` (`export_no`),
  KEY `idx_export_app` (`application_id`,`export_time`)
) ENGINE=InnoDB COMMENT='ccr_export_record 档案导出记录(§11.10)';

-- ---------- 种子:贷审会秘书岗节点指派(需求四,2026-08-14;2026-08-14 改由计划财务部总经理兼任) ----------
-- SECRETARY 节点按计划财务部+部门总经理岗位解析:计划财务部总经理兼任秘书(审核岗,同意后上送、否决即整单拦截);
-- 触发条件在路由引擎条件插节点(applySecretaryGate),此处仅配置处理人
INSERT INTO `ccr_node_assignee`
  (`id`,`tenant_id`,`flow_key`,`node_code`,`assignee_type`,`assignee_code`,`relation`,`status`,`version_no`,`create_by`,`create_time`,`del_flag`)
SELECT 2091000000000000001,'000000',NULL,'SECRETARY','DEPT','3202233931:dept_gm','OR','ACTIVE',1,1004,NOW(),'0'
WHERE NOT EXISTS (SELECT 1 FROM `ccr_node_assignee`
                  WHERE node_code='SECRETARY' AND assignee_type='DEPT' AND assignee_code='3202233931:dept_gm' AND del_flag='0');

-- ---------- 种子:六人小组委员(需求 §7.5:授信评审部分管行领导/风险管理部分管行领导/计划财务部分管行领导/授信评审部总经理/风险管理部总经理/计划财务部总经理;2026-08-14 落真实人员) ----------
-- 3 位分管行领导(系统现有 3 位 vice_president):史志明/陈开成/侯允杰;3 位部门总经理:张文伟(授信评审部 3202233943)/程欣(风险管理部 3202233942)/周瑜(计划财务部 3202233931)
-- AND 全员表决,≥4 同意通过;委员登录 roles 由 AuthController 按 SIX_PEOPLE_GROUP 指派附加 committee_member
INSERT INTO `ccr_node_assignee`
  (`id`,`tenant_id`,`flow_key`,`node_code`,`assignee_type`,`assignee_code`,`relation`,`status`,`version_no`,`create_by`,`create_time`,`del_flag`)
SELECT 2092000000000000101,'000000',NULL,'SIX_PEOPLE_GROUP','PERSON','01500064','AND','ACTIVE',1,1004,NOW(),'0'
WHERE NOT EXISTS (SELECT 1 FROM `ccr_node_assignee`
                  WHERE node_code='SIX_PEOPLE_GROUP' AND assignee_type='PERSON' AND assignee_code='01500064' AND del_flag='0');
INSERT INTO `ccr_node_assignee`
  (`id`,`tenant_id`,`flow_key`,`node_code`,`assignee_type`,`assignee_code`,`relation`,`status`,`version_no`,`create_by`,`create_time`,`del_flag`)
SELECT 2092000000000000102,'000000',NULL,'SIX_PEOPLE_GROUP','PERSON','02300273','AND','ACTIVE',1,1004,NOW(),'0'
WHERE NOT EXISTS (SELECT 1 FROM `ccr_node_assignee`
                  WHERE node_code='SIX_PEOPLE_GROUP' AND assignee_type='PERSON' AND assignee_code='02300273' AND del_flag='0');
INSERT INTO `ccr_node_assignee`
  (`id`,`tenant_id`,`flow_key`,`node_code`,`assignee_type`,`assignee_code`,`relation`,`status`,`version_no`,`create_by`,`create_time`,`del_flag`)
SELECT 2092000000000000103,'000000',NULL,'SIX_PEOPLE_GROUP','PERSON','02300744','AND','ACTIVE',1,1004,NOW(),'0'
WHERE NOT EXISTS (SELECT 1 FROM `ccr_node_assignee`
                  WHERE node_code='SIX_PEOPLE_GROUP' AND assignee_type='PERSON' AND assignee_code='02300744' AND del_flag='0');
INSERT INTO `ccr_node_assignee`
  (`id`,`tenant_id`,`flow_key`,`node_code`,`assignee_type`,`assignee_code`,`relation`,`status`,`version_no`,`create_by`,`create_time`,`del_flag`)
SELECT 2092000000000000104,'000000',NULL,'SIX_PEOPLE_GROUP','PERSON','02300483','AND','ACTIVE',1,1004,NOW(),'0'
WHERE NOT EXISTS (SELECT 1 FROM `ccr_node_assignee`
                  WHERE node_code='SIX_PEOPLE_GROUP' AND assignee_type='PERSON' AND assignee_code='02300483' AND del_flag='0');
INSERT INTO `ccr_node_assignee`
  (`id`,`tenant_id`,`flow_key`,`node_code`,`assignee_type`,`assignee_code`,`relation`,`status`,`version_no`,`create_by`,`create_time`,`del_flag`)
SELECT 2092000000000000105,'000000',NULL,'SIX_PEOPLE_GROUP','PERSON','02300040','AND','ACTIVE',1,1004,NOW(),'0'
WHERE NOT EXISTS (SELECT 1 FROM `ccr_node_assignee`
                  WHERE node_code='SIX_PEOPLE_GROUP' AND assignee_type='PERSON' AND assignee_code='02300040' AND del_flag='0');
INSERT INTO `ccr_node_assignee`
  (`id`,`tenant_id`,`flow_key`,`node_code`,`assignee_type`,`assignee_code`,`relation`,`status`,`version_no`,`create_by`,`create_time`,`del_flag`)
SELECT 2092000000000000106,'000000',NULL,'SIX_PEOPLE_GROUP','PERSON','02300222','AND','ACTIVE',1,1004,NOW(),'0'
WHERE NOT EXISTS (SELECT 1 FROM `ccr_node_assignee`
                  WHERE node_code='SIX_PEOPLE_GROUP' AND assignee_type='PERSON' AND assignee_code='02300222' AND del_flag='0');
