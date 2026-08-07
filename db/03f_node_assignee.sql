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
  `node_code`           VARCHAR(64)  NOT NULL COMMENT '节点编码:BRANCH_MANAGER/DEPT_GENERAL_MANAGER/VICE_PRESIDENT/SIX_PEOPLE_GROUP/PRESIDENT',
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
