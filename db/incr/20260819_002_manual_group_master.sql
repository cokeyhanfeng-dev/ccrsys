-- ============================================================
-- 增量 002：手工集团主数据(ccr_group / ccr_group_member)
-- 背景:集团申请支持「新增数仓未统计的集团 + 手动补录公司」。
--   数仓快照(dw_customer_group_snapshot 等)仍为权威数据源,本表仅存手工录入集团;
--   手工集团无数仓授信快照,批复总额度(approved_total_amount)手工补录,供路由定档/额度勾稽。
-- 幂等:CREATE TABLE IF NOT EXISTS,可重复执行。
-- ============================================================

USE `ccr_rate`;

-- ---------- 手工集团主数据 ----------
CREATE TABLE IF NOT EXISTS `ccr_group` (
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
  `group_no`            VARCHAR(64)  NOT NULL COMMENT '手工集团客户编号(与数仓集团不重复)',
  `group_name`          VARCHAR(255) NOT NULL COMMENT '集团名称',
  `group_type`          VARCHAR(32)  NOT NULL DEFAULT 'INDUSTRY_GROUP' COMMENT '集团类型',
  `manager_org_id`      BIGINT       NULL COMMENT '管理行(机构)',
  `group_status`        VARCHAR(16)  NOT NULL DEFAULT 'NORMAL' COMMENT '集团状态(NORMAL等)',
  `approved_total_amount` DECIMAL(20,4) NOT NULL COMMENT '批复总额度(万元,手工补录,路由定档/额度勾稽基准)',
  `currency`            VARCHAR(8)   NOT NULL DEFAULT 'CNY' COMMENT 'ISO币种编码',
  `remark`              VARCHAR(500) NULL COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_no` (`group_no`),
  KEY `idx_gp_name` (`group_name`)
) ENGINE=InnoDB COMMENT='ccr_group 手工集团主数据(数仓未统计,申请时新增)';

-- ---------- 手工集团成员 ----------
CREATE TABLE IF NOT EXISTS `ccr_group_member` (
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
  `group_no`            VARCHAR(64)  NOT NULL COMMENT '所属手工集团编号',
  `member_customer_no`  VARCHAR(64)  NOT NULL COMMENT '成员客户号(数仓未统计,手工补录)',
  `member_name`         VARCHAR(255) NOT NULL COMMENT '成员公司名称(数仓无主数据,手工录入)',
  `member_role`         VARCHAR(32)  NULL COMMENT '成员角色(CORE核心/GENERAL一般)',
  `control_relation`    VARCHAR(64)  NULL COMMENT '控制关系(控股/参股等)',
  `relation_start`      DATE         NULL COMMENT '关系起始(空=无限制)',
  `relation_end`        DATE         NULL COMMENT '关系结束(空=在团)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_gm_group_member` (`group_no`,`member_customer_no`),
  KEY `idx_gm_group` (`group_no`),
  KEY `idx_gm_member` (`member_customer_no`)
) ENGINE=InnoDB COMMENT='ccr_group_member 手工集团成员(数仓未统计公司,手动补录)';
