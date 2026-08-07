-- ============================================================
-- 客户贡献度与利率决策系统 · 快照表(ccr_snapshot_*)
-- 依据:设计文档V1.0 §9.4、§10.4/10.5、附录A.6
-- 快照包一旦进入 FROZEN 状态即禁止更新或删除;历史申请按绑定数据集版本解析
-- ============================================================

USE `ccr_rate`;

-- ---------- 快照包 ----------
CREATE TABLE IF NOT EXISTS `ccr_snapshot_bundle` (
  `id`                  BIGINT       NOT NULL,
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000',
  `business_no`         VARCHAR(64)  NOT NULL,
  `org_id`              BIGINT       NOT NULL,
  `status`              VARCHAR(32)  NOT NULL COMMENT 'FREEZING/FROZEN(提交后冻结)',
  `version_no`          INT          NOT NULL DEFAULT 1,
  `create_dept`         BIGINT       NULL,
  `create_by`           BIGINT       NOT NULL,
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       NULL,
  `update_time`         DATETIME     NULL,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0',
  `bundle_no`           VARCHAR(64)  NOT NULL COMMENT '快照包编号(唯一)',
  `application_id`      BIGINT       NOT NULL COMMENT '所属申请',
  `freeze_time`         DATETIME     NULL COMMENT '冻结时间',
  `bundle_hash`         CHAR(64)     NULL COMMENT '整包内容哈希',
  `record_count`        INT          NOT NULL DEFAULT 0 COMMENT '快照记录数',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bundle_no` (`bundle_no`),
  KEY `idx_bundle_app` (`application_id`)
) ENGINE=InnoDB COMMENT='ccr_snapshot_bundle 一次申请提交的完整快照包';

-- ---------- 快照记录(通用头结构) ----------
CREATE TABLE IF NOT EXISTS `ccr_snapshot_record` (
  `id`                  BIGINT       NOT NULL,
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000',
  `business_no`         VARCHAR(64)  NOT NULL,
  `org_id`              BIGINT       NOT NULL,
  `status`              VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
  `version_no`          INT          NOT NULL DEFAULT 1,
  `create_dept`         BIGINT       NULL,
  `create_by`           BIGINT       NOT NULL,
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       NULL,
  `update_time`         DATETIME     NULL,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0',
  `bundle_id`           BIGINT       NOT NULL COMMENT '快照包主键',
  `dataset_code`        VARCHAR(64)  NOT NULL COMMENT '数据集编码',
  `dataset_version_id`  BIGINT       NOT NULL COMMENT '数据集定义版本',
  `subject_type`        VARCHAR(32)  NOT NULL COMMENT '个人/企业/集团/成员/额度/分项/合同/借据/存款账户/担保等',
  `subject_id`          VARCHAR(128) NOT NULL COMMENT '标准主体标识',
  `source_system_code`  VARCHAR(32)  NOT NULL COMMENT '来源系统',
  `source_record_id`    VARCHAR(128) NOT NULL COMMENT '来源主键',
  `source_data_dt`      DATE         NOT NULL COMMENT '来源成功批次日期',
  `payload_hash`        CHAR(64)     NOT NULL COMMENT '内容哈希',
  `core_json`           JSON         NOT NULL COMMENT '审批必须还原的标准字段',
  `ext_json`            JSON         NULL COMMENT '按版本解析的扩展属性',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_snapshot_record` (`bundle_id`,`dataset_code`,`subject_type`,`subject_id`),
  KEY `idx_snapshot_source` (`source_system_code`,`source_record_id`),
  KEY `idx_snapshot_dt` (`source_data_dt`)
) ENGINE=InnoDB COMMENT='ccr_snapshot_record 快照记录通用头(稳定核心字段+ext_json)';

-- ---------- 快照关系(有向图) ----------
CREATE TABLE IF NOT EXISTS `ccr_snapshot_relation` (
  `id`                  BIGINT       NOT NULL,
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000',
  `business_no`         VARCHAR(64)  NOT NULL,
  `org_id`              BIGINT       NOT NULL,
  `status`              VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
  `version_no`          INT          NOT NULL DEFAULT 1,
  `create_dept`         BIGINT       NULL,
  `create_by`           BIGINT       NOT NULL,
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       NULL,
  `update_time`         DATETIME     NULL,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0',
  `bundle_id`           BIGINT       NOT NULL COMMENT '快照包主键',
  `parent_record_id`    BIGINT       NOT NULL COMMENT '父快照记录主键',
  `child_record_id`     BIGINT       NOT NULL COMMENT '子快照记录主键',
  `relation_type`       VARCHAR(32)  NOT NULL COMMENT 'GROUP_TO_MEMBER/MEMBER_TO_LIMIT/LIMIT_TO_TRANCHE/TRANCHE_TO_CONTRACT/CONTRACT_TO_NOTE/TO_GUARANTEE',
  `sequence_no`         INT          NOT NULL DEFAULT 1 COMMENT '同类型顺序',
  PRIMARY KEY (`id`),
  KEY `idx_rel_bundle` (`bundle_id`),
  KEY `idx_rel_parent` (`parent_record_id`),
  KEY `idx_rel_child` (`child_record_id`)
) ENGINE=InnoDB COMMENT='ccr_snapshot_relation 快照关系(集团→成员→额度→分项→合同→借据→担保)';

-- ---------- 质量校验结果 ----------
CREATE TABLE IF NOT EXISTS `ccr_snapshot_quality_result` (
  `id`                  BIGINT       NOT NULL,
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000',
  `business_no`         VARCHAR(64)  NOT NULL,
  `org_id`              BIGINT       NOT NULL,
  `status`              VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
  `version_no`          INT          NOT NULL DEFAULT 1,
  `create_dept`         BIGINT       NULL,
  `create_by`           BIGINT       NOT NULL,
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       NULL,
  `update_time`         DATETIME     NULL,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0',
  `bundle_id`           BIGINT       NOT NULL COMMENT '快照包主键',
  `application_id`      BIGINT       NOT NULL COMMENT '申请主键',
  `rule_code`           VARCHAR(64)  NOT NULL COMMENT '质量规则编码',
  `rule_level`          VARCHAR(16)  NOT NULL COMMENT 'PASS/WARN/BLOCK',
  `subject_type`        VARCHAR(32)  NULL COMMENT '校验对象类型',
  `subject_id`          VARCHAR(128) NULL COMMENT '校验对象标识',
  `expected_value`      VARCHAR(500) NULL COMMENT '期望值',
  `actual_value`        VARCHAR(500) NULL COMMENT '实际值',
  `message`             VARCHAR(1000) NULL COMMENT '结果说明',
  `checked_time`        DATETIME     NOT NULL COMMENT '校验时间',
  PRIMARY KEY (`id`),
  KEY `idx_quality_bundle` (`bundle_id`),
  KEY `idx_quality_app` (`application_id`,`rule_level`)
) ENGINE=InnoDB COMMENT='ccr_snapshot_quality_result 每项质量规则校验结果';
