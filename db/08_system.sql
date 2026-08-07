-- ============================================================
-- 基础系统功能:用户管理 / 权限管理(角色/菜单)
-- 说明:当前登录为开发期 mock,此处提供真实用户/角色数据表与种子
-- ============================================================

USE `ccr_rate`;

-- ---------- 用户表 ----------
CREATE TABLE IF NOT EXISTS `ccr_sys_user` (
  `id`          BIGINT       NOT NULL,
  `tenant_id`   VARCHAR(20)  NOT NULL DEFAULT '000000',
  `username`    VARCHAR(64)  NOT NULL COMMENT '登录名',
  `password`    VARCHAR(128) NOT NULL COMMENT '密码(开发期明文,接入后加密)',
  `nick_name`   VARCHAR(64)  NOT NULL COMMENT '姓名',
  `role_code`   VARCHAR(32)  NOT NULL COMMENT '角色:customer_manager/branch_manager/committee_member/president/admin',
  `org_id`      BIGINT       NOT NULL COMMENT '归属机构',
  `phone`       VARCHAR(20)  NULL,
  `email`       VARCHAR(64)  NULL,
  `status`      VARCHAR(8)   NOT NULL DEFAULT 'ENABLE' COMMENT 'ENABLE/DISABLE',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     NULL,
  `del_flag`    CHAR(1)      NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_role` (`role_code`)
) ENGINE=InnoDB COMMENT='系统用户表';

-- ---------- 角色表 ----------
CREATE TABLE IF NOT EXISTS `ccr_sys_role` (
  `id`          BIGINT       NOT NULL,
  `tenant_id`   VARCHAR(20)  NOT NULL DEFAULT '000000',
  `role_code`   VARCHAR(32)  NOT NULL COMMENT '角色编码',
  `role_name`   VARCHAR(64)  NOT NULL COMMENT '角色名称',
  `remark`      VARCHAR(200) NULL,
  `menu_ids`    VARCHAR(500) NULL COMMENT '菜单权限id(逗号分隔)',
  `status`      VARCHAR(8)   NOT NULL DEFAULT 'ENABLE',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     NULL,
  `del_flag`    CHAR(1)      NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB COMMENT='系统角色表';

-- ---------- 菜单表 ----------
CREATE TABLE IF NOT EXISTS `ccr_sys_menu` (
  `id`        BIGINT       NOT NULL,
  `tenant_id` VARCHAR(20)  NOT NULL DEFAULT '000000',
  `parent_id` BIGINT       NOT NULL DEFAULT 0 COMMENT '父菜单',
  `menu_name` VARCHAR(64)  NOT NULL COMMENT '菜单名称',
  `path`      VARCHAR(128) NULL COMMENT '前端路由',
  `perms`     VARCHAR(128) NULL COMMENT '权限标识',
  `sort_no`   INT          NOT NULL DEFAULT 1,
  `status`    VARCHAR(8)   NOT NULL DEFAULT 'ENABLE',
  `create_time` DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME   NULL,
  `del_flag`  CHAR(1)      NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_parent` (`parent_id`)
) ENGINE=InnoDB COMMENT='系统菜单表';

-- ---------- 种子:用户 ----------
INSERT INTO `ccr_sys_user` (`id`,`username`,`password`,`nick_name`,`role_code`,`org_id`,`phone`,`status`) VALUES
  (1000,'zhangsan','$2a$10$THM2OKfKEr21VQkyhtsX0.6Beq1ENtulIQJiaE8W50WR9f39io2rC','张客户经理','customer_manager',1001,'13800000001','ENABLE'),
  (1001,'wangwu','$2a$10$THM2OKfKEr21VQkyhtsX0.6Beq1ENtulIQJiaE8W50WR9f39io2rC','陈支行行长','branch_manager',1002,'13800000002','ENABLE'),
  (1002,'committee','$2a$10$THM2OKfKEr21VQkyhtsX0.6Beq1ENtulIQJiaE8W50WR9f39io2rC','李小组成员','committee_member',1003,'13800000003','ENABLE'),
  (1003,'president','$2a$10$THM2OKfKEr21VQkyhtsX0.6Beq1ENtulIQJiaE8W50WR9f39io2rC','王总行行长','president',1000,'13800000004','ENABLE'),
  (1004,'admin','$2a$10$THM2OKfKEr21VQkyhtsX0.6Beq1ENtulIQJiaE8W50WR9f39io2rC','系统管理员','admin',1000,'13800000000','ENABLE'),
  (1005,'committee2','$2a$10$THM2OKfKEr21VQkyhtsX0.6Beq1ENtulIQJiaE8W50WR9f39io2rC','小组成员二','committee_member',1003,'13800000013','ENABLE'),
  (1006,'committee3','$2a$10$THM2OKfKEr21VQkyhtsX0.6Beq1ENtulIQJiaE8W50WR9f39io2rC','小组成员三','committee_member',1003,'13800000014','ENABLE'),
  (1007,'committee4','$2a$10$THM2OKfKEr21VQkyhtsX0.6Beq1ENtulIQJiaE8W50WR9f39io2rC','小组成员四','committee_member',1003,'13800000015','ENABLE'),
  (1008,'committee5','$2a$10$THM2OKfKEr21VQkyhtsX0.6Beq1ENtulIQJiaE8W50WR9f39io2rC','小组成员五','committee_member',1003,'13800000016','ENABLE'),
  (1009,'committee6','$2a$10$THM2OKfKEr21VQkyhtsX0.6Beq1ENtulIQJiaE8W50WR9f39io2rC','小组成员六','committee_member',1003,'13800000017','ENABLE'),
  (1010,'deptgm','$2a$10$THM2OKfKEr21VQkyhtsX0.6Beq1ENtulIQJiaE8W50WR9f39io2rC','赵部门总经理','dept_gm',1003,'13800000018','ENABLE'),
  (1011,'vicepresident','$2a$10$THM2OKfKEr21VQkyhtsX0.6Beq1ENtulIQJiaE8W50WR9f39io2rC','钱分管行长','vice_president',1000,'13800000019','ENABLE'),
  (1012,'paramadmin','$2a$10$THM2OKfKEr21VQkyhtsX0.6Beq1ENtulIQJiaE8W50WR9f39io2rC','孙参数管理员','param_admin',1000,'13800000020','ENABLE'),
  (1013,'reviewer','$2a$10$THM2OKfKEr21VQkyhtsX0.6Beq1ENtulIQJiaE8W50WR9f39io2rC','周配置复核人','config_reviewer',1000,'13800000021','ENABLE'),
  (1014,'auditor','$2a$10$THM2OKfKEr21VQkyhtsX0.6Beq1ENtulIQJiaE8W50WR9f39io2rC','吴审计人员','auditor',1000,'13800000022','ENABLE')
ON DUPLICATE KEY UPDATE nick_name=VALUES(nick_name);

-- ---------- 种子:角色(菜单权限与实际前端菜单一致) ----------
INSERT INTO `ccr_sys_role` (`id`,`role_code`,`role_name`,`remark`,`menu_ids`) VALUES
  (2000,'customer_manager','客户经理','仅PC发起申请与录入','1,2,3,4,5'),
  (2001,'branch_manager','支行行长','权限内审批与承诺跟踪','1,4,5'),
  (2002,'committee_member','审批小组成员','匿名表决','1,5'),
  (2003,'president','总行行长','最终决策','1,5'),
  (2004,'admin','系统管理员','可见全部功能与数据','1,2,3,4,5,6,7,8,9'),
  (2005,'dept_gm','部门总经理','权限内审批与调价','1,4,5'),
  (2006,'vice_president','分管行领导','权限内审批与调价','1,4,5'),
  (2007,'param_admin','参数管理员','LPR/规则/边界参数维护草稿','1,9'),
  (2008,'config_reviewer','配置复核人','参数复核与发布','1,9'),
  (2009,'auditor','审计人员','授权档案/表决汇总/导出查询','1,5')
ON DUPLICATE KEY UPDATE role_name=VALUES(role_name), menu_ids=VALUES(menu_ids);

-- ---------- 种子:菜单(与实际前端侧边栏一致) ----------
INSERT INTO `ccr_sys_menu` (`id`,`parent_id`,`menu_name`,`path`,`perms`,`sort_no`) VALUES
  (1,0,'工作台','/overview',NULL,1),
  (2,0,'贷款利率申请','/application/loan',NULL,2),
  (3,0,'存款利率申请','/application/deposit',NULL,3),
  (4,0,'贡献度跟踪','/commitment',NULL,4),
  (5,0,'历史','/history',NULL,5),
  (6,0,'用户管理','/system/user','system:user',6),
  (7,0,'权限管理','/system/role','system:role',7),
  (8,0,'流程配置','/system/flow','system:flow',8),
  (9,0,'参数管理','/system/params','system:params',9)
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name);
