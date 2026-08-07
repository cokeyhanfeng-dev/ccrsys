-- ============================================================
-- 客户贡献度与利率决策系统 · 配置与元数据表(ccr_*)
-- 依据:设计文档V1.0 §8.4、§9.6、§4.3
-- 版本管理:草稿/待复核/已生效/已停用;生效后不可原位修改,只能创建新版本;生效区间不重叠
-- ============================================================

USE `ccr_rate`;

-- ---------- LPR 版本 ----------
CREATE TABLE IF NOT EXISTS `ccr_lpr_version` (
  `id`                  BIGINT       NOT NULL,
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000',
  `business_no`         VARCHAR(64)  NOT NULL,
  `org_id`              BIGINT       NOT NULL,
  `status`              VARCHAR(32)  NOT NULL COMMENT 'DRAFT/REVIEW/EFFECTIVE/INVALID',
  `version_no`          INT          NOT NULL DEFAULT 1,
  `create_dept`         BIGINT       NULL,
  `create_by`           BIGINT       NOT NULL,
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       NULL,
  `update_time`         DATETIME     NULL,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0',
  `version_code`        VARCHAR(32)  NOT NULL COMMENT '版本号(唯一)',
  `lpr_1y`              DECIMAL(9,6) NOT NULL COMMENT '一年期LPR(%)',
  `lpr_5y`              DECIMAL(9,6) NOT NULL COMMENT '五年期以上LPR(%)',
  `effective_from`      DATETIME     NOT NULL COMMENT '生效时间',
  `effective_to`        DATETIME     NULL COMMENT '失效时间',
  `publish_by`          BIGINT       NULL COMMENT '发布人(双人复核)',
  `review_by`           BIGINT       NULL COMMENT '复核人',
  `publish_time`        DATETIME     NULL COMMENT '发布时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lpr_version` (`version_code`),
  KEY `idx_lpr_effective` (`effective_from`,`effective_to`)
) ENGINE=InnoDB COMMENT='ccr_lpr_version LPR版本(双人复核发布)';

-- ---------- 利率规则集版本 ----------
CREATE TABLE IF NOT EXISTS `ccr_rate_rule_set` (
  `id`                  BIGINT       NOT NULL,
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000',
  `business_no`         VARCHAR(64)  NOT NULL,
  `org_id`              BIGINT       NOT NULL,
  `status`              VARCHAR(32)  NOT NULL COMMENT 'DRAFT/REVIEW/EFFECTIVE/INVALID',
  `version_no`          INT          NOT NULL DEFAULT 1,
  `create_dept`         BIGINT       NULL,
  `create_by`           BIGINT       NOT NULL,
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       NULL,
  `update_time`         DATETIME     NULL,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0',
  `set_code`            VARCHAR(32)  NOT NULL COMMENT '规则集编码(唯一)',
  `set_name`            VARCHAR(200) NOT NULL COMMENT '规则集名称',
  `effective_from`      DATETIME     NOT NULL,
  `effective_to`        DATETIME     NULL,
  `publish_by`          BIGINT       NULL,
  `review_by`           BIGINT       NULL,
  `publish_time`        DATETIME     NULL,
  `remark`              VARCHAR(500) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule_set` (`set_code`)
) ENGINE=InnoDB COMMENT='ccr_rate_rule_set 利率规则集版本';

-- ---------- 具体规则 ----------
CREATE TABLE IF NOT EXISTS `ccr_rate_rule` (
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
  `set_id`              BIGINT       NOT NULL COMMENT '规则集主键',
  `rule_code`           VARCHAR(64)  NOT NULL COMMENT '规则编码(集内唯一)',
  `rule_name`           VARCHAR(200) NOT NULL COMMENT '规则名称',
  `business_type`       VARCHAR(16)  NOT NULL COMMENT 'LOAN/DEPOSIT',
  `product_code`        VARCHAR(32)  NULL COMMENT '产品编码(空=通配)',
  `customer_type`       VARCHAR(16)  NULL COMMENT '对公/个人',
  `new_or_existing`     VARCHAR(16)  NULL COMMENT 'NEW/EXISTING',
  `state_owned_flag`    CHAR(1)      NULL COMMENT '国企属性Y/N',
  `group_credit_min`    DECIMAL(20,4) NULL COMMENT '集团授信总额下限(万元)',
  `group_credit_max`    DECIMAL(20,4) NULL COMMENT '集团授信总额上限',
  `amount_min`          DECIMAL(20,4) NULL COMMENT '申请金额下限',
  `amount_max`          DECIMAL(20,4) NULL COMMENT '申请金额上限',
  `term_min`            INT          NULL COMMENT '期限下限',
  `term_max`            INT          NULL COMMENT '期限上限',
  `term_unit`           VARCHAR(8)   NULL COMMENT '期限单位',
  `guarantee_type`      VARCHAR(32)  NULL COMMENT '担保主类型(空=通配)',
  `lpr_term`            VARCHAR(8)   NULL COMMENT 'LPR期限(1Y/5Y+)',
  `org_code`            VARCHAR(64)  NULL COMMENT '机构(空=通配)',
  `currency`            VARCHAR(8)   NULL DEFAULT 'CNY' COMMENT '币种',
  `start_node_code`     VARCHAR(64)  NOT NULL COMMENT '起始节点',
  `rate_direction`      VARCHAR(16)  NOT NULL COMMENT 'LOWER_BETTER/HIGHER_BETTER',
  `priority`            INT          NOT NULL DEFAULT 0 COMMENT '匹配优先级(低值优先)',
  `mutex_group`         VARCHAR(32)  NULL COMMENT '互斥条件组',
  `description`         VARCHAR(1000) NULL COMMENT '规则说明',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule` (`set_id`,`rule_code`),
  KEY `idx_rule_dim` (`business_type`,`product_code`,`org_code`)
) ENGINE=InnoDB COMMENT='ccr_rate_rule 具体匹配规则(唯一路由)';

-- ---------- 产品利率边界 ----------
CREATE TABLE IF NOT EXISTS `ccr_product_rate_limit` (
  `id`                  BIGINT       NOT NULL,
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000',
  `business_no`         VARCHAR(64)  NOT NULL,
  `org_id`              BIGINT       NOT NULL,
  `status`              VARCHAR(32)  NOT NULL COMMENT 'DRAFT/REVIEW/EFFECTIVE/INVALID',
  `version_no`          INT          NOT NULL DEFAULT 1,
  `create_dept`         BIGINT       NULL,
  `create_by`           BIGINT       NOT NULL,
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       NULL,
  `update_time`         DATETIME     NULL,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0',
  `product_code`        VARCHAR(32)  NOT NULL COMMENT '产品编码',
  `product_name`        VARCHAR(100) NULL COMMENT '产品名称',
  `business_type`       VARCHAR(16)  NOT NULL COMMENT 'LOAN/DEPOSIT',
  `hard_boundary_rate`  DECIMAL(9,6) NOT NULL COMMENT '业务硬边界利率(%)',
  `rate_direction`      VARCHAR(16)  NOT NULL COMMENT 'LOWER_BETTER/HIGHER_BETTER',
  `effective_from`      DATETIME     NOT NULL,
  `effective_to`        DATETIME     NULL,
  `publish_by`          BIGINT       NULL,
  `review_by`           BIGINT       NULL,
  `publish_time`        DATETIME     NULL,
  PRIMARY KEY (`id`),
  KEY `idx_product_limit` (`product_code`,`business_type`,`effective_from`,`effective_to`)
) ENGINE=InnoDB COMMENT='ccr_product_rate_limit 产品标准与业务硬边界';

-- ---------- 岗位权限边界 ----------
CREATE TABLE IF NOT EXISTS `ccr_node_permission` (
  `id`                  BIGINT       NOT NULL,
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000',
  `business_no`         VARCHAR(64)  NOT NULL,
  `org_id`              BIGINT       NOT NULL,
  `status`              VARCHAR(32)  NOT NULL COMMENT 'DRAFT/REVIEW/EFFECTIVE/INVALID',
  `version_no`          INT          NOT NULL DEFAULT 1,
  `create_dept`         BIGINT       NULL,
  `create_by`           BIGINT       NOT NULL,
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       NULL,
  `update_time`         DATETIME     NULL,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0',
  `node_code`           VARCHAR(64)  NOT NULL COMMENT '节点编码',
  `role_code`           VARCHAR(64)  NOT NULL COMMENT '岗位/角色编码',
  `business_type`       VARCHAR(16)  NOT NULL COMMENT 'LOAN/DEPOSIT',
  `boundary_min_rate`   DECIMAL(9,6) NULL COMMENT '权限下界(贷款:本岗位最低可批)',
  `boundary_max_rate`   DECIMAL(9,6) NULL COMMENT '权限上界(存款:本岗位最高可批)',
  `effective_from`      DATETIME     NOT NULL,
  `effective_to`        DATETIME     NULL,
  `publish_by`          BIGINT       NULL,
  `review_by`           BIGINT       NULL,
  `publish_time`        DATETIME     NULL,
  PRIMARY KEY (`id`),
  KEY `idx_node_perm` (`node_code`,`role_code`,`business_type`,`effective_from`,`effective_to`)
) ENGINE=InnoDB COMMENT='ccr_node_permission 节点权限边界(岗位最低/最高可批利率)';

-- ---------- 数据集定义 ----------
CREATE TABLE IF NOT EXISTS `ccr_dataset_definition` (
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
  `dataset_code`        VARCHAR(64)  NOT NULL COMMENT '数据集编码(唯一)',
  `dataset_name`        VARCHAR(200) NOT NULL COMMENT '数据集名称',
  `source_system_code`  VARCHAR(32)  NOT NULL COMMENT '来源系统',
  `schema_version`      VARCHAR(32)  NOT NULL DEFAULT '1.0' COMMENT '当前结构版本',
  `retention_days`      INT          NOT NULL DEFAULT 365 COMMENT '保留明细周期',
  `active_flag`         CHAR(1)      NOT NULL DEFAULT 'Y' COMMENT '是否启用',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dataset_code` (`dataset_code`)
) ENGINE=InnoDB COMMENT='ccr_dataset_definition 数据集定义(元数据)';

-- ---------- 数据集结构版本 ----------
CREATE TABLE IF NOT EXISTS `ccr_dataset_version` (
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
  `dataset_id`          BIGINT       NOT NULL COMMENT '数据集定义主键',
  `version_code`        VARCHAR(32)  NOT NULL COMMENT '结构版本号',
  `effective_from`      DATETIME     NOT NULL COMMENT '生效时间',
  `effective_to`        DATETIME     NULL COMMENT '失效时间',
  `schema_json`         JSON         NULL COMMENT '字段结构定义',
  `supersede_reason`    VARCHAR(500) NULL COMMENT '演进原因',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dataset_ver` (`dataset_id`,`version_code`)
) ENGINE=InnoDB COMMENT='ccr_dataset_version 数据集结构版本(字段演进/历史解析)';

-- ---------- 字段定义 ----------
CREATE TABLE IF NOT EXISTS `ccr_field_definition` (
  `id`                  BIGINT       NOT NULL,
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000',
  `business_no`         VARCHAR(64)  NOT NULL,
  `org_id`              BIGINT       NOT NULL,
  `status`              VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INVALID(废弃不删)',
  `version_no`          INT          NOT NULL DEFAULT 1,
  `create_dept`         BIGINT       NULL,
  `create_by`           BIGINT       NOT NULL,
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       NULL,
  `update_time`         DATETIME     NULL,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0',
  `dataset_id`          BIGINT       NOT NULL COMMENT '数据集定义主键',
  `field_code`          VARCHAR(64)  NOT NULL COMMENT '字段编码',
  `field_name`          VARCHAR(200) NULL COMMENT '字段名称',
  `field_type`          VARCHAR(32)  NOT NULL COMMENT '类型',
  `unit`                VARCHAR(16)  NULL COMMENT '单位',
  `sensitive_level`     VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT 'NONE/CIPHER/HASH(敏感)',
  `display_flag`        CHAR(1)      NOT NULL DEFAULT 'Y' COMMENT '是否展示',
  `validation_json`     JSON         NULL COMMENT '校验配置',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_field` (`dataset_id`,`field_code`)
) ENGINE=InnoDB COMMENT='ccr_field_definition 字段定义(改名=新编码,旧标记废止)';

-- ---------- 指标定义 ----------
CREATE TABLE IF NOT EXISTS `ccr_metric_definition` (
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
  `metric_code`         VARCHAR(40)  NOT NULL COMMENT '稳定指标编码(唯一)',
  `metric_name`         VARCHAR(100) NOT NULL COMMENT '指标名称',
  `value_type`          VARCHAR(20)  NOT NULL COMMENT 'AVG_BALANCE/INCOME/CONTRIBUTION_AMOUNT',
  `metric_scope`        VARCHAR(32)  NULL COMMENT '适用范围',
  `unit`                VARCHAR(16)  NOT NULL COMMENT '单位',
  `current_calc_version` VARCHAR(32) NOT NULL COMMENT '当前折算版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_metric_code` (`metric_code`)
) ENGINE=InnoDB COMMENT='ccr_metric_definition 贡献度和风险指标定义';

-- ---------- 源映射 ----------
CREATE TABLE IF NOT EXISTS `ccr_source_mapping` (
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
  `dataset_id`          BIGINT       NOT NULL COMMENT '数据集定义主键',
  `source_field`        VARCHAR(64)  NOT NULL COMMENT '数仓字段',
  `biz_field`           VARCHAR(64)  NOT NULL COMMENT '业务标准字段',
  `mapping_version`     VARCHAR(32)  NOT NULL COMMENT '映射版本',
  `effective_from`      DATETIME     NOT NULL,
  `effective_to`        DATETIME     NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mapping` (`dataset_id`,`source_field`,`biz_field`,`mapping_version`)
) ENGINE=InnoDB COMMENT='ccr_source_mapping 数仓字段到业务标准字段的版本化映射';

-- ---------- 校验规则 ----------
CREATE TABLE IF NOT EXISTS `ccr_validation_rule` (
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
  `rule_code`           VARCHAR(64)  NOT NULL COMMENT '规则编码(唯一)',
  `rule_name`           VARCHAR(200) NOT NULL COMMENT '规则名称',
  `rule_level`          VARCHAR(16)  NOT NULL COMMENT 'PASS/WARN/BLOCK',
  `data_type`           VARCHAR(32)  NULL COMMENT '适用数据类型',
  `rule_expression`     VARCHAR(500) NULL COMMENT '规则表达式/配置',
  `message_template`    VARCHAR(1000) NULL COMMENT '提示模板',
  `enabled_flag`        CHAR(1)      NOT NULL DEFAULT 'Y',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_validation_rule` (`rule_code`)
) ENGINE=InnoDB COMMENT='ccr_validation_rule 数据质量和资料完整性校验规则';

-- ---------- 展示配置 ----------
CREATE TABLE IF NOT EXISTS `ccr_display_schema` (
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
  `schema_code`         VARCHAR(64)  NOT NULL COMMENT '展示方案编码(唯一)',
  `dataset_id`          BIGINT       NOT NULL COMMENT '数据集定义主键',
  `group_name`          VARCHAR(100) NULL COMMENT '分组',
  `field_order`         INT          NOT NULL DEFAULT 1 COMMENT '字段顺序',
  `field_code`          VARCHAR(64)  NOT NULL COMMENT '展示字段',
  `readonly_flag`       CHAR(1)      NOT NULL DEFAULT 'Y' COMMENT '是否只读',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_display_schema` (`schema_code`,`dataset_id`,`field_code`)
) ENGINE=InnoDB COMMENT='ccr_display_schema 参考数据展示配置(字段顺序/分组/只读)';

-- ---------- 配置变更审计日志 ----------
CREATE TABLE IF NOT EXISTS `ccr_config_change_log` (
  `id`                  BIGINT       NOT NULL,
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000',
  `business_no`         VARCHAR(64)  NOT NULL,
  `org_id`              BIGINT       NOT NULL,
  `status`              VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
  `version_no`          INT          NOT NULL DEFAULT 1 COMMENT '配置记录版本号',
  `create_dept`         BIGINT       NULL,
  `create_by`           BIGINT       NOT NULL,
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       NULL,
  `update_time`         DATETIME     NULL,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0',
  `config_type`         VARCHAR(32)  NOT NULL COMMENT '配置域:LPR/MATRIX/RULE_SET/PRODUCT_LIMIT',
  `config_id`           BIGINT       NOT NULL COMMENT '配置记录主键',
  `action`              VARCHAR(16)  NOT NULL COMMENT 'CREATE/SUBMIT/PUBLISH/DISABLE/REJECT',
  `old_json`            JSON         NULL COMMENT '变更前快照(JSON)',
  `new_json`            JSON         NULL COMMENT '变更后快照(JSON)',
  `opinion`             VARCHAR(1000) NULL COMMENT '复核/驳回意见(驳回必填)',
  `operator_id`         BIGINT       NOT NULL COMMENT '操作人',
  `operate_time`        DATETIME     NOT NULL COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_cfg_log` (`config_type`,`config_id`,`operate_time`)
) ENGINE=InnoDB COMMENT='ccr_config_change_log 配置变更审计日志(§8A.2)';

-- ---------- Outbox 可靠事件 ----------
CREATE TABLE IF NOT EXISTS `ccr_outbox_event` (
  `id`                  BIGINT       NOT NULL,
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000',
  `business_no`         VARCHAR(64)  NOT NULL,
  `org_id`              BIGINT       NOT NULL,
  `status`              VARCHAR(32)  NOT NULL COMMENT 'PENDING/SENT/FAILED/DELIVERED',
  `version_no`          INT          NOT NULL DEFAULT 1,
  `create_dept`         BIGINT       NULL,
  `create_by`           BIGINT       NOT NULL,
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       NULL,
  `update_time`         DATETIME     NULL,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0',
  `event_no`            VARCHAR(64)  NOT NULL COMMENT '事件编号(唯一,消费端幂等)',
  `event_type`          VARCHAR(64)  NOT NULL COMMENT '事件类型(FLOW_START/NOTIFY/RESOLUTION_CALLBACK)',
  `biz_type`            VARCHAR(32)  NULL COMMENT '业务类型',
  `biz_id`              BIGINT       NULL COMMENT '业务主键',
  `payload`             JSON         NOT NULL COMMENT '事件载荷',
  `retry_count`         INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
  `max_retry`           INT          NOT NULL DEFAULT 5 COMMENT '最大重试',
  `next_retry_time`     DATETIME     NULL COMMENT '下次重试时间',
  `last_error`          VARCHAR(2000) NULL COMMENT '最近错误',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_no` (`event_no`),
  KEY `idx_outbox_status` (`status`,`next_retry_time`)
) ENGINE=InnoDB COMMENT='ccr_outbox_event 可靠流程/消息/回传事件(Outbox模式)';
