-- ============================================================
-- 基础系统功能:机构表(数据权限控制基础,详设 §5.1.1/§5.4/§10.3.20)
-- 机构层级:总行(HEAD) → 部门(DEPT)/支行(BRANCH) → 网点(NETWORK);集团管理机构(GROUP)独立挂载
-- 机构编码 org_code:层级前缀数字编码(唯一、禁改),如 1000 总行 → 100201 支行 → 10020101 网点
-- 数据权限:机构范围按 org_code 前缀匹配(LIKE '100201%' 覆盖支行及其下辖网点),不做递归查询
-- 2026-08-14:删除 dept_code 字母编码列,机构编码统一 org_code(部门归属/矩阵/指派/数仓全部对齐 org_code)
-- ============================================================

USE `ccr_rate`;

CREATE TABLE IF NOT EXISTS `ccr_sys_dept` (
  `id`          BIGINT       NOT NULL,
  `tenant_id`   VARCHAR(20)  NOT NULL DEFAULT '000000',
  `org_code`    VARCHAR(32)  NOT NULL COMMENT '机构编码(层级前缀数字码,唯一,禁改):1000总行/1001xx部门/1002xx支行/支行码+两位为网点',
  `ancestors`   VARCHAR(255) NOT NULL DEFAULT '' COMMENT '祖先链(机构id逗号分隔),如 0,1000,1002',
  `branch_code` VARCHAR(32)  NULL COMMENT '支行编码:BRANCH=自身org_code;NETWORK=所属支行org_code;DEPT/HEAD为空',
  `dept_name`   VARCHAR(64)  NOT NULL COMMENT '机构名称',
  `parent_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT '父机构id',
  `org_type`    VARCHAR(16)  NOT NULL COMMENT 'HEAD总行/DEPT部门/BRANCH支行/NETWORK网点/GROUP集团管理机构',
  `manager`     VARCHAR(64)  NULL COMMENT '负责人',
  `status`      VARCHAR(8)   NOT NULL DEFAULT 'ENABLE',
  `sort_no`     INT          NOT NULL DEFAULT 1,
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     NULL,
  `del_flag`    CHAR(1)      NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_org_code` (`org_code`),
  KEY `idx_parent` (`parent_id`),
  KEY `idx_branch_code` (`branch_code`)
) ENGINE=InnoDB COMMENT='机构表(数据权限控制,编码前缀匹配)';

-- ---------- 种子:机构层级(前缀编码;既有 dept id 主键保持不变,ccr_sys_user.org_id 引用不断) ----------
-- 编码段:1000 总行;1001xx 部门;1002xx 支行(城东 100201/城西 100202/宜城 100203);支行码+两位 = 网点
-- 部门归属(矩阵 dept_code/指派/ccr_dept_vp)统一使用机构 org_code:100101公司金融部/100102授信评审部/100103零售金融部
INSERT INTO `ccr_sys_dept`
  (`id`,`org_code`,`ancestors`,`branch_code`,`dept_name`,`parent_id`,`org_type`,`manager`,`status`,`sort_no`) VALUES
  (1000,'1000','0',NULL,'总行',0,'HEAD','王行长','ENABLE',1),
  (1003,'100101','0,1000',NULL,'公司金融部',1000,'DEPT','张总经理','ENABLE',2),
  (1004,'100102','0,1000',NULL,'授信评审部',1000,'DEPT','刘总经理','ENABLE',3),
  (1005,'100103','0,1000',NULL,'零售金融部',1000,'DEPT','赵总经理','ENABLE',4),
  (1006,'100104','0,1000',NULL,'风险管理部',1000,'DEPT','孙总经理','ENABLE',5),
  (1008,'100105','0,1000',NULL,'计划财务部',1000,'DEPT','钱总经理','ENABLE',6),
  (1001,'100201','0,1000','100201','城东支行',1000,'BRANCH','陈主任','ENABLE',7),
  (1002,'100202','0,1000','100202','城西支行',1000,'BRANCH','李主任','ENABLE',8),
  (1007,'100203','0,1000','100203','宜城支行',1000,'BRANCH','周主任','ENABLE',9),
  (1009,'10020101','0,1000,1001','100201','城东支行营业部',1001,'NETWORK',NULL,'ENABLE',10),
  (1010,'10020201','0,1000,1002','100202','城西支行营业部',1002,'NETWORK',NULL,'ENABLE',11),
  (1011,'10020301','0,1000,1007','100203','宜城支行营业部',1007,'NETWORK',NULL,'ENABLE',12)
ON DUPLICATE KEY UPDATE
  org_code=VALUES(org_code), ancestors=VALUES(ancestors),
  branch_code=VALUES(branch_code), dept_name=VALUES(dept_name), parent_id=VALUES(parent_id),
  org_type=VALUES(org_type), manager=VALUES(manager), sort_no=VALUES(sort_no);

-- ---------- 种子:用户机构对齐 ----------
UPDATE `ccr_sys_user` SET org_id=1001 WHERE username='zhangsan';
UPDATE `ccr_sys_user` SET org_id=1001 WHERE username='wangwu';
UPDATE `ccr_sys_user` SET org_id=1007 WHERE username='lisi';
UPDATE `ccr_sys_user` SET org_id=1000 WHERE username IN ('committee','president','admin');
