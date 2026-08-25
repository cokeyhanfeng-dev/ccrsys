-- ============================================================
-- 增量 003(2026-08-25):他行融资概要可编辑 + 概要/明细对应校验
-- 需求:融资概要(数仓 dw_credit_financing_summary 带出)随申请可编辑持久化;
--   新增/存量客户概要均可在申请页编辑,提交时与融资明细加总做对应校验(§2026-08-25)。
-- 幂等:CREATE TABLE IF NOT EXISTS,可重复执行。
-- ============================================================

USE `ccr_rate`;

CREATE TABLE IF NOT EXISTS `ccr_application_credit_summary` (
  `id`                  BIGINT       NOT NULL COMMENT '雪花主键',
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000' COMMENT '租户标识',
  `business_no`         VARCHAR(64)  NOT NULL COMMENT '表内唯一业务编号',
  `org_id`              BIGINT       NOT NULL COMMENT '数据归属机构',
  `status`              VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '业务状态编码',
  `version_no`          INT          NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
  `create_dept`         BIGINT       NULL COMMENT '创建部门',
  `create_by`           BIGINT       NOT NULL COMMENT '创建人',
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`           BIGINT       NULL COMMENT '最后修改人',
  `update_time`         DATETIME     NULL COMMENT '最后修改时间',
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0' COMMENT '逻辑删除标识(0否 1是)',
  `application_id`      BIGINT       NOT NULL COMMENT '申请主键',
  `lender_count`        INT          NULL COMMENT '授信机构数',
  `credit_amount_total` DECIMAL(20,4) NULL COMMENT '他行授信总额(万元)',
  `used_amount_total`   DECIMAL(20,4) NULL COMMENT '已用额度合计(万元)',
  `loan_account_count`  INT          NULL COMMENT '未结清笔数',
  `overdue_account_count` INT        NULL COMMENT '逾期账户数',
  `overdue_balance`     DECIMAL(20,4) NULL COMMENT '逾期余额(万元)',
  `npl_balance`         DECIMAL(20,4) NULL COMMENT '不良贷款余额(万元)',
  `special_mention_balance` DECIMAL(20,4) NULL COMMENT '关注类余额(万元)',
  `external_guarantee_balance` DECIMAL(20,4) NULL COMMENT '对外担保余额(万元)',
  PRIMARY KEY (`id`),
  KEY `idx_credit_summary_app` (`application_id`)
) ENGINE=InnoDB COMMENT='ccr_application_credit_summary 他行融资概要快照(申请随单可编辑,与明细对应校验,§2026-08-25)';
