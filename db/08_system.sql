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
  (1001,'wangwu','$2a$10$THM2OKfKEr21VQkyhtsX0.6Beq1ENtulIQJiaE8W50WR9f39io2rC','陈支行行长','branch_manager',1001,'13800000002','ENABLE'),
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
  (1013,'reviewer','$2a$10$THM2OKfKEr21VQkyhtsX0.6Beq1ENtulIQJiaE8W50WR9f39io2rC','周配置复核人','config_reviewer',1000,'13800000021','ENABLE'),
  (1014,'auditor','$2a$10$THM2OKfKEr21VQkyhtsX0.6Beq1ENtulIQJiaE8W50WR9f39io2rC','吴审计人员','auditor',1000,'13800000022','ENABLE'),
  (1015,'lisi','$2a$10$THM2OKfKEr21VQkyhtsX0.6Beq1ENtulIQJiaE8W50WR9f39io2rC','李客户经理','customer_manager',1007,'13800000023','ENABLE')
ON DUPLICATE KEY UPDATE nick_name=VALUES(nick_name);

-- ---------- 种子:角色(菜单权限与实际前端菜单一致;无独立"参数管理员"角色,详设 §5.2) ----------
INSERT INTO `ccr_sys_role` (`id`,`role_code`,`role_name`,`remark`,`menu_ids`) VALUES
  (2000,'customer_manager','客户经理','仅PC发起申请与录入','1,2,3,4,5,11'),
  (2001,'branch_manager','支行行长','权限内审批与承诺跟踪','1,4,5,10,11'),
  (2002,'committee_member','审批小组成员','匿名表决','1,5,10,11'),
  (2003,'president','总行行长','最终决策','1,5,10,11'),
  (2004,'admin','系统管理员','可见全部功能与数据','1,2,3,4,5,6,7,8,9,10,11,12,13'),
  (2005,'dept_gm','部门总经理','权限内审批与调价','1,4,5,10,11'),
  (2006,'vice_president','分管行领导','权限内审批与调价','1,4,5,10,11'),
  (2008,'config_reviewer','配置复核人','参数复核与发布','1,9,11'),
  (2009,'auditor','审计人员','授权档案/表决汇总/导出查询','1,5,11,12')
ON DUPLICATE KEY UPDATE role_name=VALUES(role_name), menu_ids=VALUES(menu_ids);

-- ---------- 种子:菜单(与实际前端侧边栏一致) ----------
INSERT INTO `ccr_sys_menu` (`id`,`parent_id`,`menu_name`,`path`,`perms`,`sort_no`) VALUES
  (1,0,'工作台','/overview',NULL,1),
  (2,0,'贷款利率申请','/application/loan',NULL,2),
  (3,0,'存款利率申请','/application/deposit',NULL,3),
  (10,0,'利率审批','/approval','ccr:approval',4),
  (4,0,'贡献度跟踪','/commitment',NULL,5),
  (5,0,'历史','/history',NULL,6),
  (11,0,'数据中心','/datacenter',NULL,7),
  (12,0,'审计管理','/audit','ccr:audit',8),
  (6,0,'用户管理','/system/user','system:user',9),
  (7,0,'权限管理','/system/role','system:role',10),
  (13,0,'机构管理','/system/dept','system:dept',11),
  (8,0,'流程配置','/system/flow','system:flow',12),
  (9,0,'参数管理','/system/params','system:params',13)
ON DUPLICATE KEY UPDATE menu_name=VALUES(menu_name), sort_no=VALUES(sort_no);

-- ---------- 清理:取消 param_admin 角色(详设 §5.2,无独立"参数管理员"角色;兼容旧库重跑) ----------
DELETE FROM `ccr_sys_user` WHERE `username`='paramadmin';
DELETE FROM `ccr_sys_role` WHERE `role_code`='param_admin';

-- ---------- 用户-机构-岗位绑定表(详设 §5.1/§10.3.20 sys_user_post) ----------
CREATE TABLE IF NOT EXISTS `ccr_sys_user_post` (
  `id`          BIGINT       NOT NULL,
  `tenant_id`   VARCHAR(20)  NOT NULL DEFAULT '000000',
  `user_id`     BIGINT       NOT NULL COMMENT '用户id',
  `org_id`      BIGINT       NOT NULL COMMENT '机构id(ccr_sys_dept.id)',
  `post_code`   VARCHAR(32)  NOT NULL COMMENT '岗位编码(与角色码对齐:customer_manager/branch_manager/...)',
  `is_default`  CHAR(1)      NOT NULL DEFAULT '0' COMMENT '是否默认机构/岗位:1是0否(每用户仅一条默认)',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     NULL,
  `del_flag`    CHAR(1)      NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_org_post` (`user_id`,`org_id`,`post_code`),
  KEY `idx_up_org` (`org_id`)
) ENGINE=InnoDB COMMENT='用户-机构-岗位绑定表(一用户可绑多机构/岗位组合,默认唯一)';

-- ---------- 种子:用户-机构-岗位绑定(与 ccr_sys_user 的 org_id/role_code 对齐,均为默认绑定) ----------
INSERT INTO `ccr_sys_user_post` (`id`,`user_id`,`org_id`,`post_code`,`is_default`) VALUES
  (1,1000,1001,'customer_manager','1'),
  (2,1001,1001,'branch_manager','1'),
  (3,1015,1007,'customer_manager','1'),
  (4,1002,1003,'committee_member','1'),
  (5,1005,1003,'committee_member','1'),
  (6,1006,1003,'committee_member','1'),
  (7,1007,1003,'committee_member','1'),
  (8,1008,1003,'committee_member','1'),
  (9,1009,1003,'committee_member','1'),
  (10,1010,1003,'dept_gm','1'),
  (11,1011,1000,'vice_president','1'),
  (12,1003,1000,'president','1'),
  (13,1004,1000,'admin','1'),
  (14,1013,1000,'config_reviewer','1'),
  (15,1014,1000,'auditor','1')
ON DUPLICATE KEY UPDATE is_default=VALUES(is_default);
