-- ============================================================
-- 客户贡献度与利率决策系统 · 承诺与跟踪表(ccr_commitment_*/ccr_tracking_*)
-- 依据:设计文档V1.0 §9.5、§11、附录A.7
-- 目标公式: INCREMENT=(当前值-基线值)/承诺新增值;TARGET_BALANCE=当前值/目标值;CUMULATIVE=期间累计实际/期间目标
-- 状态: PENDING/TRACKING/AT_RISK/ACHIEVED/EXPIRED_UNMET/DATA_PENDING/TERMINATED/SUPERSEDED
-- ============================================================

USE `ccr_rate`;

-- ---------- 承诺计划 ----------
CREATE TABLE IF NOT EXISTS `ccr_commitment_plan` (
  `id`                  BIGINT       NOT NULL,
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000',
  `business_no`         VARCHAR(64)  NOT NULL,
  `org_id`              BIGINT       NOT NULL,
  `status`              VARCHAR(32)  NOT NULL COMMENT 'PENDING/TRACKING/AT_RISK/ACHIEVED/EXPIRED_UNMET/DATA_PENDING/TERMINATED/SUPERSEDED',
  `version_no`          INT          NOT NULL DEFAULT 1,
  `create_dept`         BIGINT       NULL,
  `create_by`           BIGINT       NOT NULL,
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       NULL,
  `update_time`         DATETIME     NULL,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0',
  `plan_no`             VARCHAR(64)  NOT NULL COMMENT '承诺计划编号(唯一)',
  `resolution_id`       BIGINT       NOT NULL COMMENT '来源决议主键',
  `scope_type`          VARCHAR(16)  NOT NULL COMMENT 'INDIVIDUAL/CORPORATE_SINGLE/MEMBER/GROUP',
  `customer_no`         VARCHAR(64)  NULL COMMENT '个人或企业客户号',
  `group_no`            VARCHAR(64)  NULL COMMENT '集团号',
  `member_customer_no`  VARCHAR(64)  NULL COMMENT '成员客户号(成员级承诺)',
  `allocation_mode`     VARCHAR(16)  NULL COMMENT 'FIXED_ALLOCATION固定分配/GROUP_SHARED集团共享',
  `start_date`          DATE         NOT NULL COMMENT '开始日期',
  `end_date`            DATE         NOT NULL COMMENT '到期日期',
  `policy_version_id`   BIGINT       NULL COMMENT '冻结跟踪策略版本',
  `member_frozen_json`  JSON         NULL COMMENT '冻结成员集合快照',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_plan_no` (`plan_no`),
  KEY `idx_plan_resolution` (`resolution_id`),
  KEY `idx_plan_subject` (`scope_type`,`customer_no`,`group_no`,`member_customer_no`)
) ENGINE=InnoDB COMMENT='ccr_commitment_plan 承诺跟踪计划(审批通过后生成)';

-- ---------- 承诺指标 ----------
CREATE TABLE IF NOT EXISTS `ccr_commitment_metric` (
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
  `plan_id`             BIGINT       NOT NULL COMMENT '承诺计划主键',
  `metric_code`         VARCHAR(40)  NOT NULL COMMENT '稳定指标编码',
  `metric_name`         VARCHAR(100) NULL COMMENT '指标名称',
  `target_type`         VARCHAR(24)  NOT NULL COMMENT 'INCREMENT/TARGET_BALANCE/CUMULATIVE',
  `baseline_value`      DECIMAL(20,4) NOT NULL COMMENT '基线值(万元)',
  `target_value`        DECIMAL(20,4) NOT NULL COMMENT '目标值(万元)',
  `unit`                VARCHAR(16)  NOT NULL COMMENT '单位',
  `calc_version`        VARCHAR(32)  NOT NULL COMMENT '计算版本',
  `metric_scope`        VARCHAR(32)  NULL COMMENT '指标范围',
  PRIMARY KEY (`id`),
  KEY `idx_metric_plan` (`plan_id`),
  UNIQUE KEY `uk_plan_metric` (`plan_id`,`metric_code`)
) ENGINE=InnoDB COMMENT='ccr_commitment_metric 承诺指标(基线/目标/单位/计算版本)';

-- ---------- 集团承诺成员分配 ----------
CREATE TABLE IF NOT EXISTS `ccr_commitment_member_alloc` (
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
  `plan_id`             BIGINT       NOT NULL COMMENT '承诺计划主键',
  `metric_id`           BIGINT       NOT NULL COMMENT '承诺指标主键',
  `member_customer_no`  VARCHAR(64)  NOT NULL COMMENT '成员客户号',
  `allocated_target`    DECIMAL(20,4) NOT NULL COMMENT '成员目标(万元)',
  `allocated_baseline`  DECIMAL(20,4) NOT NULL COMMENT '成员基线',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_alloc` (`metric_id`,`member_customer_no`),
  KEY `idx_alloc_plan` (`plan_id`)
) ENGINE=InnoDB COMMENT='ccr_commitment_member_alloc 集团承诺成员分配(固定分配合计=集团目标)';

-- ---------- 跟踪评估(每次校验结果,不覆盖历史) ----------
CREATE TABLE IF NOT EXISTS `ccr_tracking_evaluation` (
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
  `plan_id`             BIGINT       NOT NULL COMMENT '承诺计划主键',
  `metric_id`           BIGINT       NOT NULL COMMENT '承诺指标主键',
  `data_dt`             DATE         NOT NULL COMMENT '数据日期',
  `actual_value`        DECIMAL(20,4) NOT NULL COMMENT '实际值(数仓最新成功批次)',
  `progress_ratio`      DECIMAL(9,6) NULL COMMENT '时间进度',
  `achievement_ratio`   DECIMAL(9,6) NULL COMMENT '达成率',
  `risk_level`          VARCHAR(16)  NULL COMMENT 'NORMAL/WATCH/AT_RISK',
  `result_status`       VARCHAR(16)  NOT NULL COMMENT 'ON_TRACK/AT_RISK/ACHIEVED/EXPIRED_UNMET/DATA_PENDING',
  `calc_version`        VARCHAR(32)  NOT NULL COMMENT '计算版本',
  `source_batch_id`     VARCHAR(64)  NULL COMMENT '数仓来源批次',
  `remark`              VARCHAR(500) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_evaluation` (`plan_id`,`metric_id`,`data_dt`,`calc_version`),
  KEY `idx_eval_metric` (`metric_id`),
  KEY `idx_eval_date` (`data_dt`)
) ENGINE=InnoDB COMMENT='ccr_tracking_evaluation 跟踪评估(每次定时校验生成)';

-- ---------- 跟踪策略主表 ----------
CREATE TABLE IF NOT EXISTS `ccr_tracking_policy` (
  `id`                  BIGINT       NOT NULL,
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000',
  `business_no`         VARCHAR(64)  NOT NULL,
  `org_id`              BIGINT       NOT NULL,
  `status`              VARCHAR(32)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/REVIEW/EFFECTIVE/INVALID',
  `version_no`          INT          NOT NULL DEFAULT 1,
  `create_dept`         BIGINT       NULL,
  `create_by`           BIGINT       NOT NULL,
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       NULL,
  `update_time`         DATETIME     NULL,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0',
  `policy_no`           VARCHAR(64)  NOT NULL COMMENT '策略编号(唯一)',
  `policy_name`         VARCHAR(200) NOT NULL COMMENT '策略名称',
  `metric_code`         VARCHAR(40)  NOT NULL COMMENT '指标编码',
  `business_type`       VARCHAR(32)  NULL COMMENT '业务类型(空=全行默认)',
  `org_code`            VARCHAR(64)  NULL COMMENT '机构(空=通用)',
  `priority`            INT          NOT NULL DEFAULT 0 COMMENT '优先级(指标+业务+机构 > 指标+业务 > 指标默认 > 全行默认)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_policy_no` (`policy_no`),
  KEY `idx_policy_metric` (`metric_code`,`org_code`)
) ENGINE=InnoDB COMMENT='ccr_tracking_policy 跟踪策略主表(版本化)';

-- ---------- 策略版本 ----------
CREATE TABLE IF NOT EXISTS `ccr_tracking_policy_version` (
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
  `policy_id`           BIGINT       NOT NULL COMMENT '策略主表主键',
  `version_code`        VARCHAR(32)  NOT NULL COMMENT '版本号(如V1)',
  `effective_from`      DATETIME     NOT NULL COMMENT '生效时间',
  `effective_to`        DATETIME     NULL COMMENT '失效时间(空=有效)',
  `check_frequency`     VARCHAR(32)  NOT NULL DEFAULT 'DAILY' COMMENT '校验频率',
  `first_check_time`    DATETIME     NULL COMMENT '首次校验时间',
  `data_tolerance_days` INT          NOT NULL DEFAULT 2 COMMENT '数据容忍天数',
  `backfill_scope`      VARCHAR(200) NULL COMMENT '补跑范围',
  `config_json`         JSON         NULL COMMENT '阈值/提醒等配置',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_policy_ver` (`policy_id`,`version_code`),
  UNIQUE KEY `uk_policy_effective` (`policy_id`,`effective_from`)
) ENGINE=InnoDB COMMENT='ccr_tracking_policy_version 策略版本(生效区间不重叠)';

-- ---------- 阈值 ----------
CREATE TABLE IF NOT EXISTS `ccr_tracking_threshold` (
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
  `policy_version_id`   BIGINT       NOT NULL COMMENT '策略版本主键',
  `threshold_type`      VARCHAR(32)  NOT NULL COMMENT 'TIME_PROGRESS/ACHIEVEMENT_RATE/CONSECUTIVE_DECLINE/NEAR_EXPIRY',
  `threshold_value`     DECIMAL(9,6) NOT NULL COMMENT '阈值数值(比率)',
  `risk_level`          VARCHAR(16)  NOT NULL COMMENT 'NORMAL/WATCH/AT_RISK',
  `compare_operator`    VARCHAR(8)   NOT NULL COMMENT '> >= < <=',
  PRIMARY KEY (`id`),
  KEY `idx_threshold_pv` (`policy_version_id`)
) ENGINE=InnoDB COMMENT='ccr_tracking_threshold 阈值(时间进度/达成率/连续下降/临近到期)';

-- ---------- 通知规则 ----------
CREATE TABLE IF NOT EXISTS `ccr_notification_rule` (
  `id`                  BIGINT       NOT NULL,
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000',
  `business_no`         VARCHAR(64)  NOT NULL,
  `org_id`              BIGINT       NOT NULL,
  `status`              VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
  `version_no`          INT          NOT NULL DEFAULT 1 COMMENT '规则版本号(内容变更递增;ccr_notification_log.rule_version_id 引用本表当前版本id,发送时冻结)',
  `create_dept`         BIGINT       NULL,
  `create_by`           BIGINT       NOT NULL,
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       NULL,
  `update_time`         DATETIME     NULL,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0',
  `rule_no`             VARCHAR(64)  NOT NULL COMMENT '规则编号(唯一)',
  `rule_name`           VARCHAR(200) NOT NULL COMMENT '规则名称',
  `trigger_level`       VARCHAR(16)  NOT NULL COMMENT '触发等级(WATCH/AT_RISK/EXPIRED)',
  `channel`             VARCHAR(32)  NOT NULL COMMENT 'APP/内部平台/消息',
  `repeat_interval_hours` INT        NOT NULL DEFAULT 24 COMMENT '重复提醒间隔',
  `max_repeat_count`    INT          NOT NULL DEFAULT 3 COMMENT '最大提醒次数',
  `cool_down_hours`     INT          NOT NULL DEFAULT 0 COMMENT '冷却时间',
  `upgrade_rule_json`   JSON         NULL COMMENT '升级路径配置',
  `message_template`    VARCHAR(2000) NULL COMMENT '消息模板',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notice_rule_no` (`rule_no`)
) ENGINE=InnoDB COMMENT='ccr_notification_rule 通知规则(触发/渠道/频率/冷却/升级)';

-- ---------- 提醒对象规则 ----------
CREATE TABLE IF NOT EXISTS `ccr_notification_recipient` (
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
  `rule_id`             BIGINT       NOT NULL COMMENT '通知规则主键',
  `recipient_type`      VARCHAR(32)  NOT NULL COMMENT 'CUSTOMER_MANAGER/GROUP_MANAGER/MEMBER_MANAGER/BRANCH_MANAGER/GROUP_ORG_LEADER/ORIGINAL_APPROVER/ORIGINAL_VOTER/DEPT_GM/VICE_PRESIDENT/ROLE/USER/ORG_POST',
  `recipient_value`     VARCHAR(200) NULL COMMENT '对象值(角色编码/用户id/岗位)',
  `level_condition`     VARCHAR(16)  NULL COMMENT '适用预警等级(高等级按配置升级)',
  PRIMARY KEY (`id`),
  KEY `idx_recipient_rule` (`rule_id`)
) ENGINE=InnoDB COMMENT='ccr_notification_recipient 动态提醒对象规则';

-- ---------- 通知日志 ----------
CREATE TABLE IF NOT EXISTS `ccr_notification_log` (
  `id`                  BIGINT       NOT NULL,
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000',
  `business_no`         VARCHAR(64)  NOT NULL,
  `org_id`              BIGINT       NOT NULL,
  `status`              VARCHAR(32)  NOT NULL DEFAULT 'SENDING' COMMENT 'SENDING/SENT/FAILED/RECEIVED/ARCHIVED',
  `version_no`          INT          NOT NULL DEFAULT 1,
  `create_dept`         BIGINT       NULL,
  `create_by`           BIGINT       NOT NULL,
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       NULL,
  `update_time`         DATETIME     NULL,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0',
  `evaluation_id`       BIGINT       NULL COMMENT '跟踪评估主键',
  `rule_version_id`     BIGINT       NOT NULL COMMENT '通知规则主键(引用ccr_notification_rule.id,取发送时点该规则当前版本;规则历史版本由服务层按version_no留痕,本表不做悬空引用)',
  `recipient_type`      VARCHAR(32)  NOT NULL COMMENT '接收对象类型',
  `recipient_id`        VARCHAR(128) NOT NULL COMMENT '接收对象标识',
  `channel`             VARCHAR(32)  NOT NULL COMMENT '发送渠道',
  `message_key`         VARCHAR(64)  NOT NULL COMMENT '消息去重键(唯一)',
  `message_content`     VARCHAR(2000) NULL COMMENT '消息内容',
  `send_status`         VARCHAR(16)  NOT NULL COMMENT 'SUCCESS/FAILED/RETRYING',
  `retry_count`         INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
  `send_time`           DATETIME     NULL COMMENT '发送时间',
  `receipt_time`        DATETIME     NULL COMMENT '回执时间',
  `error_message`       VARCHAR(500) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_message_key` (`message_key`),
  KEY `idx_notify_recipient` (`recipient_type`,`recipient_id`,`send_time`)
) ENGINE=InnoDB COMMENT='ccr_notification_log 消息日志(唯一message_key防重复发送)';
