-- ============================================================
-- 客户贡献度与利率决策系统 · 六人表决表(ccr_*)
-- 依据:设计文档V1.0 §9.3、§7.4/7.5、附录A.7
-- 匿名性:ccr_ballot 中真实人员信息只允许表决服务和审计权限读取(§9.3)
-- 唯一约束:一人一票、一批次分项唯一、全部提交后计票
-- ============================================================

USE `ccr_rate`;

-- ---------- 表决批次 ----------
CREATE TABLE IF NOT EXISTS `ccr_vote_round` (
  `id`                  BIGINT       NOT NULL,
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000',
  `business_no`         VARCHAR(64)  NOT NULL,
  `org_id`              BIGINT       NOT NULL,
  `status`              VARCHAR(32)  NOT NULL COMMENT 'CREATED/VOTING/COUNTING/PASSED/FAILED/CANCELLED',
  `version_no`          INT          NOT NULL DEFAULT 1,
  `create_dept`         BIGINT       NULL,
  `create_by`           BIGINT       NOT NULL,
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       NULL,
  `update_time`         DATETIME     NULL,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0',
  `application_id`      BIGINT       NOT NULL COMMENT '所属申请',
  `round_no`            INT          NOT NULL COMMENT '申请内顺序编号(唯一)',
  `round_name`          VARCHAR(200) NULL COMMENT '批次名称',
  `voter_count`         INT          NOT NULL DEFAULT 6 COMMENT '应投人数',
  `required_count`      INT          NOT NULL DEFAULT 4 COMMENT '通过所需票数',
  `round_start_time`    DATETIME     NULL COMMENT '批次开始时间',
  `round_end_time`      DATETIME     NULL COMMENT '批次结束时间',
  `create_reason`       VARCHAR(500) NULL COMMENT '创建说明',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_round_seq` (`application_id`,`round_no`),
  KEY `idx_round_status` (`status`)
) ENGINE=InnoDB COMMENT='ccr_vote_round 表决批次(6人名单批次创建时冻结)';

-- ---------- 批次分项 ----------
CREATE TABLE IF NOT EXISTS `ccr_vote_round_item` (
  `id`                  BIGINT       NOT NULL,
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000',
  `business_no`         VARCHAR(64)  NOT NULL,
  `org_id`              BIGINT       NOT NULL,
  `status`              VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUBMITTED',
  `version_no`          INT          NOT NULL DEFAULT 1,
  `create_dept`         BIGINT       NULL,
  `create_by`           BIGINT       NOT NULL,
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       NULL,
  `update_time`         DATETIME     NULL,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0',
  `round_id`            BIGINT       NOT NULL COMMENT '表决批次主键',
  `pricing_item_id`     BIGINT       NOT NULL COMMENT '定价分项主键',
  `sequence_no`         INT          NOT NULL DEFAULT 1 COMMENT '批内顺序',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_round_pricing` (`pricing_item_id`,`round_id`),
  KEY `idx_round_item` (`round_id`)
) ENGINE=InnoDB COMMENT='ccr_vote_round_item 批次分项(RETURNED否决退回后可重提入新批次;同一时间仅一个进行中批次由服务层保证)';

-- ---------- 委员任务 ----------
CREATE TABLE IF NOT EXISTS `ccr_vote_assignment` (
  `id`                  BIGINT       NOT NULL,
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000',
  `business_no`         VARCHAR(64)  NOT NULL,
  `org_id`              BIGINT       NOT NULL,
  `status`              VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUBMITTED/REPLACED',
  `version_no`          INT          NOT NULL DEFAULT 1,
  `create_dept`         BIGINT       NULL,
  `create_by`           BIGINT       NOT NULL,
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       NULL,
  `update_time`         DATETIME     NULL,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0',
  `round_id`            BIGINT       NOT NULL COMMENT '表决批次主键',
  `voter_user_id`       BIGINT       NOT NULL COMMENT '委员用户id(批次创建时冻结)',
  `voter_anonym_no`     VARCHAR(16)  NULL COMMENT '匿名编号(如A-F)',
  `substitute_from_user_id` BIGINT    NULL COMMENT '替补自原委员(替补场景)',
  `substitute_reason`   VARCHAR(500) NULL COMMENT '替补原因',
  `submit_time`         DATETIME     NULL COMMENT '全部项提交时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_round_voter` (`round_id`,`voter_user_id`),
  KEY `idx_voter` (`voter_user_id`)
) ENGINE=InnoDB COMMENT='ccr_vote_assignment 委员任务(替补由授权人员发起并记录)';

-- ---------- 实际票据 ----------
CREATE TABLE IF NOT EXISTS `ccr_ballot` (
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
  `round_id`            BIGINT       NOT NULL COMMENT '表决批次主键',
  `pricing_item_id`     BIGINT       NOT NULL COMMENT '定价分项主键',
  `voter_user_id_cipher` VARCHAR(512) NOT NULL COMMENT '委员用户id密文',
  `voter_user_hash`     CHAR(64)     NOT NULL COMMENT '委员用户id查询哈希',
  `vote_choice`         VARCHAR(16)  NOT NULL COMMENT 'APPROVE通过/REJECT否决',
  `vote_comment`        VARCHAR(1000) NULL COMMENT '意见',
  `submit_time`         DATETIME     NOT NULL COMMENT '提交时间',
  `idempotency_key`     VARCHAR(64)  NULL COMMENT '幂等键',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ballot` (`round_id`,`pricing_item_id`,`voter_user_hash`),
  UNIQUE KEY `uk_ballot_idem` (`idempotency_key`),
  KEY `idx_ballot_pricing` (`pricing_item_id`)
) ENGINE=InnoDB COMMENT='ccr_ballot 实际票据(一人一票,提交后不可修改;人员信息加密)';

-- ---------- 分项计票结果 ----------
CREATE TABLE IF NOT EXISTS `ccr_vote_result` (
  `id`                  BIGINT       NOT NULL,
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000',
  `business_no`         VARCHAR(64)  NOT NULL,
  `org_id`              BIGINT       NOT NULL,
  `status`              VARCHAR(32)  NOT NULL DEFAULT 'COUNTING' COMMENT 'COUNTING/PASSED/FAILED',
  `version_no`          INT          NOT NULL DEFAULT 1,
  `create_dept`         BIGINT       NULL,
  `create_by`           BIGINT       NOT NULL,
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       NULL,
  `update_time`         DATETIME     NULL,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0',
  `round_id`            BIGINT       NOT NULL COMMENT '表决批次主键',
  `pricing_item_id`     BIGINT       NOT NULL COMMENT '定价分项主键',
  `required_count`      INT          NOT NULL COMMENT '通过所需票数',
  `submitted_count`     INT          NOT NULL COMMENT '已提交票数',
  `approve_count`       INT          NOT NULL DEFAULT 0 COMMENT '赞成数',
  `reject_count`        INT          NOT NULL DEFAULT 0 COMMENT '否决数',
  `result`              VARCHAR(16)  NOT NULL COMMENT 'PASS/FAIL',
  `count_time`          DATETIME     NOT NULL COMMENT '计票时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_vote_result_pricing` (`pricing_item_id`),
  KEY `idx_vote_result_round` (`round_id`)
) ENGINE=InnoDB COMMENT='ccr_vote_result 分项计票结果(六人全部提交后一次性生成)';

-- ---------- 行长分项决策 ----------
CREATE TABLE IF NOT EXISTS `ccr_president_decision` (
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
  `pricing_item_id`     BIGINT       NOT NULL COMMENT '定价分项主键(唯一)',
  `decision`            VARCHAR(16)  NOT NULL COMMENT 'APPROVE同意利率/VETO一票否决',
  `opinion`             VARCHAR(1000) NULL COMMENT '意见(一票否决必填)',
  `president_user_id`   BIGINT       NOT NULL COMMENT '行长用户id',
  `decision_time`       DATETIME     NOT NULL COMMENT '决策时间',
  `business_version`    INT          NOT NULL COMMENT '业务版本号(乐观锁)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_president_pricing` (`pricing_item_id`)
) ENGINE=InnoDB COMMENT='ccr_president_decision 行长分项决策(只接收表决通过分项)';
