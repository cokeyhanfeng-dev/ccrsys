-- ============================================================
-- 基础系统功能:机构表(数据权限控制基础,PRD §12 数据权限)
-- 机构层级:总行 → 分行/部门 → 支行;用户按 org_id 归属机构
-- 数据权限:支行仅见本支行贡献度与数据(查询按机构过滤)
-- ============================================================

USE `ccr_rate`;

CREATE TABLE IF NOT EXISTS `ccr_sys_dept` (
  `id`          BIGINT       NOT NULL,
  `tenant_id`   VARCHAR(20)  NOT NULL DEFAULT '000000',
  `dept_code`   VARCHAR(32)  NOT NULL COMMENT '机构编码',
  `dept_name`   VARCHAR(64)  NOT NULL COMMENT '机构名称',
  `parent_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT '父机构id',
  `org_type`    VARCHAR(16)  NOT NULL COMMENT 'HEAD总行/BRANCH分行/DEPT部门/SUB_BRANCH支行',
  `manager`     VARCHAR(64)  NULL COMMENT '负责人',
  `status`      VARCHAR(8)   NOT NULL DEFAULT 'ENABLE',
  `sort_no`     INT          NOT NULL DEFAULT 1,
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     NULL,
  `del_flag`    CHAR(1)      NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dept_code` (`dept_code`),
  KEY `idx_parent` (`parent_id`)
) ENGINE=InnoDB COMMENT='机构表(数据权限控制)';

-- ---------- 种子:机构层级 ----------
INSERT INTO `ccr_sys_dept` (`id`,`dept_code`,`dept_name`,`parent_id`,`org_type`,`manager`,`sort_no`) VALUES
  (1000,'HQ','总行',0,'HEAD','王行长',1),
  (1001,'CDZH','城东支行',1000,'SUB_BRANCH','陈主任',2),
  (1002,'CXZH','城西支行',1000,'SUB_BRANCH','李主任',3),
  (1003,'GSB','公司金融部',1000,'DEPT','张总经理',4),
  (1004,'SXSB','授信评审部',1000,'DEPT','刘总经理',5),
  (1005,'LSB','零售金融部',1000,'DEPT','赵总经理',6),
  (1006,'FHSH','分行',1000,'BRANCH','孙行长',7)
ON DUPLICATE KEY UPDATE dept_name=VALUES(dept_name);

-- ---------- 种子:用户机构对齐 ----------
UPDATE `ccr_sys_user` SET org_id=1001 WHERE username='zhangsan';
UPDATE `ccr_sys_user` SET org_id=1001 WHERE username='wangwu';
UPDATE `ccr_sys_user` SET org_id=1000 WHERE username IN ('committee','president','admin');
