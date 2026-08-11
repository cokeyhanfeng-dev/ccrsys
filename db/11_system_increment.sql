-- ============================================================
-- 客户贡献度与利率决策系统 · lane-02 增量 DDL(幂等)
-- 依据:设计文档V1.0 §10.3.21(ccr_relation)/§10.3.24(dw_org_dim)/§10.3.25(sys_org)
-- 内容:sys_org 机构档案 + dw_org_dim 机构维度落地 + ccr_relation 关联人绑定
--      + ccr_application.apply_branch_code(数据权限 DEPT 级过滤字段,幂等 ALTER)
-- 说明:全部 CREATE TABLE IF NOT EXISTS + 种子 ON DUPLICATE KEY,可重复执行
-- ============================================================

USE `ccr_rate`;

-- ---------- 机构档案(sys_org,§10.3.25) ----------
-- 业务机构档案主数据:资质字段(营业执照/金融许可证/社会信用代码)由机构管理页手工维护,
-- org_code 与 ccr_sys_dept.org_code 唯一对齐;数仓不产资质,资质以本表为准
CREATE TABLE IF NOT EXISTS `sys_org` (
  `id`                  BIGINT       NOT NULL COMMENT '雪花主键',
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000' COMMENT '租户标识',
  `org_code`            VARCHAR(32)  NOT NULL COMMENT '机构编号(对齐 sys_dept.org_code 层级前缀,唯一禁改)',
  `org_name`            VARCHAR(128) NOT NULL COMMENT '机构全称(中文)',
  `short_name`          VARCHAR(64)  NULL COMMENT '机构简称',
  `parent_org_code`     VARCHAR(32)  NULL COMMENT '上级机构编号(总行为空或0)',
  `parent_org_name`     VARCHAR(128) NULL COMMENT '上级机构名称(冗余)',
  `org_level_code`      VARCHAR(8)   NOT NULL COMMENT '机构层级代码(1总行/2分行/3支行/4网点,对齐 dw_org_dim.org_level)',
  `business_license_no` VARCHAR(64)  NULL COMMENT '营业执照号码',
  `financial_license_no` VARCHAR(64) NULL COMMENT '金融许可证',
  `credit_code`         VARCHAR(18)  NOT NULL COMMENT '社会信用代码(18位,非空唯一)',
  `org_type`            VARCHAR(16)  NOT NULL COMMENT '机构类型(HEAD/DEPT/BRANCH/NETWORK,对齐 sys_dept.org_type)',
  `branch_code`         VARCHAR(32)  NULL COMMENT '所属支行编码(对齐 dw_org_dim.branch_code)',
  `status`              CHAR(1)      NOT NULL DEFAULT '0' COMMENT '启用0/停用1(停用前置校验同 sys_dept)',
  `remark`              VARCHAR(255) NULL COMMENT '备注',
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  `create_by`           BIGINT       NULL COMMENT '创建人',
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       NULL,
  `update_time`         DATETIME     NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_org_code` (`org_code`),
  UNIQUE KEY `uk_sys_org_credit` (`credit_code`),
  KEY `idx_org_parent` (`parent_org_code`)
) ENGINE=InnoDB COMMENT='sys_org 机构主数据(机构档案,§10.3.25)';

-- ---------- 机构维度落地(dw_org_dim,§10.3.24) ⚠️ 已决策弃用 ----------
-- 2026-08-11 业务确认:机构维度**不纳入数仓契约(去冗余)**,完全由系统内维护
-- (ccr_sys_dept 机构树 + sys_org 机构档案);数仓不做机构主数据推送。
-- 本表定义保留备用,系统侧不再消费(无查询/无推送)。
-- 原说明:数仓机构主数据落地,本系统只读;org_code 与 sys_dept.org_code 层级前缀对齐,
-- 资质字段以 sys_org 为准(本表不含资质)
CREATE TABLE IF NOT EXISTS `dw_org_dim` (
  `etl_md5`         BIGINT       NOT NULL COMMENT '主键(自增)',
  `data_dt`         DATE         NOT NULL COMMENT '数据日期(批次标识,取最新)',
  `org_code`        VARCHAR(32)  NOT NULL COMMENT '机构编码(与 sys_dept.org_code 层级前缀一致)',
  `org_name`        VARCHAR(128) NOT NULL COMMENT '机构名称',
  `parent_org_code` VARCHAR(32)  NULL COMMENT '上级机构编码(总行为空或0)',
  `org_level`       INT          NOT NULL COMMENT '机构层级(1总行/2分行/3支行/4网点)',
  `org_type`        VARCHAR(16)  NOT NULL COMMENT '机构类型(HEAD/DEPT/BRANCH/NETWORK)',
  `branch_code`     VARCHAR(32)  NULL COMMENT '所属支行编码(BRANCH=自身;NETWORK=所属支行)',
  `org_status`      CHAR(1)      NOT NULL DEFAULT '0' COMMENT '机构状态(0启用/1停用)',
  `snapshot_ts`     DATETIME     NOT NULL COMMENT '快照时间',
  PRIMARY KEY (`etl_md5`),
  KEY `idx_org_dim_dt` (`org_code`,`data_dt`)
) ENGINE=InnoDB COMMENT='dw_org_dim 机构维度表(数仓落地,§10.3.24)';

-- ---------- 关联人绑定(ccr_relation,§10.3.21) ----------
-- 关联人唯一绑定:一个关联人(证件号)全行范围只能绑定一个客户/集团;
-- 录入即绑定、暂不支持解绑;uk_relation_cert 并发兜底
CREATE TABLE IF NOT EXISTS `ccr_relation` (
  `id`                  BIGINT       NOT NULL COMMENT '雪花主键',
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000' COMMENT '租户标识',
  `org_id`              BIGINT       NULL COMMENT '数据归属机构',
  `status`              VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态(本表暂仅ACTIVE)',
  `version_no`          INT          NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
  `create_dept`         BIGINT       NULL COMMENT '创建部门',
  `create_by`           BIGINT       NULL COMMENT '创建人',
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       NULL,
  `update_time`         DATETIME     NULL,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0' COMMENT '逻辑删除(本功能暂不支持解绑,保留兜底)',
  `cert_type`           VARCHAR(16)  NOT NULL COMMENT '证件类型:USCC对公(统一社会信用代码)/ID_CARD对私(身份证号)',
  `cert_no`             VARCHAR(64)  NOT NULL COMMENT '证件号(必填,全行唯一判重键)',
  `relation_name`       VARCHAR(128) NULL COMMENT '姓名/企业名称',
  `relation_type`       VARCHAR(32)  NULL COMMENT '关系说明',
  `customer_no`         VARCHAR(64)  NULL COMMENT '绑定客户号(单户场景=申请主客户;集团场景为空)',
  `group_no`            VARCHAR(64)  NULL COMMENT '集团客户编号(集团场景绑定对象;单户场景为空)',
  `bind_application_no` VARCHAR(64)  NULL COMMENT '绑定来源申请号(留痕)',
  `source`              VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL手工/MATCH后台匹配',
  `bind_time`           DATETIME     NULL COMMENT '绑定时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_relation_cert` (`cert_type`,`cert_no`,`del_flag`),
  KEY `idx_rel_customer` (`customer_no`),
  KEY `idx_rel_group` (`group_no`),
  KEY `idx_rel_application` (`bind_application_no`)
) ENGINE=InnoDB COMMENT='ccr_relation 关联人绑定表(§6.2/§10.3.21)';

-- ---------- 机构档案种子(对齐 ccr_sys_dept 12 机构) ----------
INSERT INTO `sys_org`
  (`id`,`org_code`,`org_name`,`short_name`,`parent_org_code`,`parent_org_name`,`org_level_code`,
   `business_license_no`,`financial_license_no`,`credit_code`,`org_type`,`branch_code`,`status`) VALUES
  (1000,'1000','总行','总行',NULL,NULL,'1',NULL,NULL,'913200000000000001','HEAD',NULL,'0'),
  (1003,'100101','公司金融部','公司金融部','1000','总行','2',NULL,NULL,'913200000000000002','DEPT',NULL,'0'),
  (1004,'100102','授信评审部','授信评审部','1000','总行','2',NULL,NULL,'913200000000000003','DEPT',NULL,'0'),
  (1005,'100103','零售金融部','零售金融部','1000','总行','2',NULL,NULL,'913200000000000004','DEPT',NULL,'0'),
  (1006,'100104','风险管理部','风险管理部','1000','总行','2',NULL,NULL,'913200000000000005','DEPT',NULL,'0'),
  (1008,'100105','计划财务部','计划财务部','1000','总行','2',NULL,NULL,'913200000000000006','DEPT',NULL,'0'),
  (1001,'100201','城东支行','城东支行','1000','总行','3',NULL,NULL,'913200000000000007','BRANCH','100201','0'),
  (1002,'100202','城西支行','城西支行','1000','总行','3',NULL,NULL,'913200000000000008','BRANCH','100202','0'),
  (1007,'100203','宜城支行','宜城支行','1000','总行','3',NULL,NULL,'913200000000000009','BRANCH','100203','0'),
  (1009,'10020101','城东支行营业部','城东营业部','100201','城东支行','4',NULL,NULL,'913200000000000010','NETWORK','100201','0'),
  (1010,'10020201','城西支行营业部','城西营业部','100202','城西支行','4',NULL,NULL,'913200000000000011','NETWORK','100202','0'),
  (1011,'10020301','宜城支行营业部','宜城营业部','100203','宜城支行','4',NULL,NULL,'913200000000000012','NETWORK','100203','0')
ON DUPLICATE KEY UPDATE
  org_name=VALUES(org_name), short_name=VALUES(short_name), parent_org_code=VALUES(parent_org_code),
  parent_org_name=VALUES(parent_org_name), org_level_code=VALUES(org_level_code),
  credit_code=VALUES(credit_code), org_type=VALUES(org_type), branch_code=VALUES(branch_code),
  status=VALUES(status);

-- ---------- 机构维度 mock(单批次 data_dt=2026-08-01) ----------
INSERT INTO `dw_org_dim`
  (`etl_md5`,`data_dt`,`org_code`,`org_name`,`parent_org_code`,`org_level`,`org_type`,`branch_code`,`org_status`,`snapshot_ts`) VALUES
  (10002,'2026-08-01','1000','总行',NULL,1,'HEAD',NULL,'0','2026-08-01 06:00:00'),
  (10003,'2026-08-01','100101','公司金融部','1000',2,'DEPT',NULL,'0','2026-08-01 06:00:00'),
  (10004,'2026-08-01','100102','授信评审部','1000',2,'DEPT',NULL,'0','2026-08-01 06:00:00'),
  (10005,'2026-08-01','100103','零售金融部','1000',2,'DEPT',NULL,'0','2026-08-01 06:00:00'),
  (10006,'2026-08-01','100104','风险管理部','1000',2,'DEPT',NULL,'0','2026-08-01 06:00:00'),
  (10007,'2026-08-01','100105','计划财务部','1000',2,'DEPT',NULL,'0','2026-08-01 06:00:00'),
  (10008,'2026-08-01','100201','城东支行','1000',3,'BRANCH','100201','0','2026-08-01 06:00:00'),
  (10009,'2026-08-01','100202','城西支行','1000',3,'BRANCH','100202','0','2026-08-01 06:00:00'),
  (10010,'2026-08-01','100203','宜城支行','1000',3,'BRANCH','100203','0','2026-08-01 06:00:00'),
  (10011,'2026-08-01','10020101','城东支行营业部','100201',4,'NETWORK','100201','0','2026-08-01 06:00:00'),
  (10012,'2026-08-01','10020201','城西支行营业部','100202',4,'NETWORK','100202','0','2026-08-01 06:00:00'),
  (10013,'2026-08-01','10020301','宜城支行营业部','100203',4,'NETWORK','100203','0','2026-08-01 06:00:00')
ON DUPLICATE KEY UPDATE
  org_name=VALUES(org_name), parent_org_code=VALUES(parent_org_code),
  org_level=VALUES(org_level), org_type=VALUES(org_type), branch_code=VALUES(branch_code);

-- ---------- ccr_application 加 apply_branch_code(数据权限 DEPT 级过滤字段,幂等 ALTER) ----------
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS,用 information_schema 判断
DROP PROCEDURE IF EXISTS `ccr_add_apply_branch_code`;
DELIMITER $$
CREATE PROCEDURE `ccr_add_apply_branch_code`()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA='ccr_rate' AND TABLE_NAME='ccr_application'
                   AND COLUMN_NAME='apply_branch_code') THEN
    ALTER TABLE `ccr_application`
      ADD COLUMN `apply_branch_code` VARCHAR(32) NULL
      COMMENT '申请支行编码(提交时按申请人机构反查,数据权限DEPT级前缀过滤,§5.4)';
  END IF;
END$$
DELIMITER ;
CALL `ccr_add_apply_branch_code`();
DROP PROCEDURE `ccr_add_apply_branch_code`;

-- ---------- 节点权限边界种子(ccr_node_permission,联调补录 §8.2 岗位最低可批利率) ----------
-- 背景:03e_config 仅建表无种子,空表导致 approve 权限判定短路(perm=null→视为可终审),
-- 支行行长越权终审(rate=3.1 应上送小组却直接 APPROVED_LEVEL)。补与权限矩阵一致的岗位下界:
--   BRANCH_MANAGER/DEPT_GENERAL_MANAGER = LPR1Y+40BP = 3.4(对公新增非国企<5000万 部门总经理线)
--   VICE_PRESIDENT                      = LPR1Y+20BP = 3.2(分管行长线)
-- 固定 id 实现 ON DUPLICATE KEY 幂等;effective_from 取早于首个批次
INSERT INTO `ccr_node_permission`
  (`id`,`tenant_id`,`business_no`,`org_id`,`status`,`version_no`,`del_flag`,
   `node_code`,`role_code`,`business_type`,`boundary_min_rate`,`effective_from`,`create_by`) VALUES
  (8001,'000000','NODE_PERM_LOAN_SEED',1000,'EFFECTIVE',1,'0','BRANCH_MANAGER','branch_manager','LOAN',3.400000,'2026-01-01 00:00:00',1004),
  (8002,'000000','NODE_PERM_LOAN_SEED',1000,'EFFECTIVE',1,'0','DEPT_GENERAL_MANAGER','dept_gm','LOAN',3.400000,'2026-01-01 00:00:00',1004),
  (8003,'000000','NODE_PERM_LOAN_SEED',1000,'EFFECTIVE',1,'0','VICE_PRESIDENT','vice_president','LOAN',3.200000,'2026-01-01 00:00:00',1004)
ON DUPLICATE KEY UPDATE
  boundary_min_rate=VALUES(boundary_min_rate), status=VALUES(status), effective_from=VALUES(effective_from);

-- ---------- 部门归属(ccr_pricing_item.dept_code,幂等 ALTER;§D16a 部门分流) ----------
-- 矩阵路由透出的部门归属编码落库到分项,部门总经理/分管行长节点按分项 dept_code 解析处理人
DROP PROCEDURE IF EXISTS `ccr_add_pricing_item_dept_code`;
DELIMITER $$
CREATE PROCEDURE `ccr_add_pricing_item_dept_code`()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA='ccr_rate' AND TABLE_NAME='ccr_pricing_item'
                   AND COLUMN_NAME='dept_code') THEN
    ALTER TABLE `ccr_pricing_item`
      ADD COLUMN `dept_code` VARCHAR(64) NULL
      COMMENT '部门归属编码(矩阵透出并提交冻结:GSB公司金融部/SXSB授信评审部/LSB零售金融部,§D16a)';
  END IF;
END$$
DELIMITER ;
CALL `ccr_add_pricing_item_dept_code`();
DROP PROCEDURE `ccr_add_pricing_item_dept_code`;

-- ---------- 部门分管行长映射(ccr_dept_vp,§D16a) ----------
-- 分管行领导与部门多对多:一个部门可配多个分管行长、一个分管行长可分管多个部门;
-- 部门总经理按「dept_code→机构下 dept_gm 角色用户」解析,分管行长按本表按 dept_code 精确映射。
CREATE TABLE IF NOT EXISTS `ccr_dept_vp` (
  `id`             BIGINT       NOT NULL COMMENT '雪花主键',
  `tenant_id`      VARCHAR(20)  NOT NULL DEFAULT '000000' COMMENT '租户标识',
  `dept_code`      VARCHAR(64)  NOT NULL COMMENT '部门归属编码(对齐 ccr_rate_matrix.dept_code:GSB/SXSB/LSB)',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='部门-分管行领导映射(§D16a;一人可分管多部门,纯配置)';
