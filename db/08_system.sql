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
  `password`    VARCHAR(128) NOT NULL COMMENT '密码(BCrypt加密;统一初始密码 Yxnsh@1a3s,首登强制改密)',
  `nick_name`   VARCHAR(64)  NOT NULL COMMENT '姓名',
  `role_code`   VARCHAR(32)  NOT NULL COMMENT '角色:customer_manager/branch_manager/committee_member/president/admin',
  `org_id`      BIGINT       NOT NULL COMMENT '归属机构',
  `phone`       VARCHAR(20)  NULL,
  `email`       VARCHAR(64)  NULL,
  `status`      VARCHAR(8)   NOT NULL DEFAULT 'ENABLE' COMMENT 'ENABLE/DISABLE',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME     NULL,
  `del_flag`    CHAR(1)      NOT NULL DEFAULT '0',
  `pwd_change_flag` CHAR(1)  NOT NULL DEFAULT '1' COMMENT '是否需强制改密:1需改密/0已改',
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

-- ---------- 种子:用户(仅系统兜底角色:president/admin/reviewer/auditor) ----------
-- 客户经理/支行行长/部门总经理真实账号见下方真实数据段;六人小组/秘书/分管行长由 ccr_node_assignee 指派真实用户,
-- 早期 mock 账号(zhangsan/lisi/deptgm/committee*6/vicepresident/secretary)已清理(2026-08-17)
INSERT INTO `ccr_sys_user` (`id`,`username`,`password`,`nick_name`,`role_code`,`org_id`,`phone`,`status`) VALUES
  (1003,'president','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','王总行行长','president',1000,'13800000004','ENABLE'),
  (1004,'admin','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','系统管理员','admin',1000,'13800000000','ENABLE'),
  (1013,'reviewer','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','周配置复核人','config_reviewer',1000,'13800000021','ENABLE'),
  (1014,'auditor','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','吴审计人员','auditor',1000,'13800000022','ENABLE')
ON DUPLICATE KEY UPDATE nick_name=VALUES(nick_name);

-- ---------- 种子:真实支行行长(2026-08-14,来源数仓 dws.ccr_sys_user 用户信息表) ----------
-- 20 位一支行一行长(源表 30 位:跳过机构缺失 5 位:3051陈江/3042蒋远/0191马坚强/3078何艳/3044程瑞;
-- 再按"一支行一行长"去重移除 5 位:城东秦朝群/丁蜀潘超超、唐雨华/高塍刘琪/广汇施敏);
-- 登录名=工号,密码=统一初始密码 Yxnsh@1a3s(共享 BCrypt,首登强制改密);org_id 为机构 id(对齐 09_system_dept 迁移后的新 id);
-- 删除测试支行行长 wangwu;BRANCH_MANAGER 节点指派改 DEPT 总行前缀,按申请机构解析本支行真实行长(见 11_system_increment.sql 指派段)
INSERT INTO `ccr_sys_user` (`id`,`username`,`password`,`nick_name`,`role_code`,`org_id`,`phone`,`status`) VALUES
  (1100,'023010401','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','马科辉','branch_manager',1026,'13800000201','ENABLE'),
  (1101,'023010851','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','曹聪','branch_manager',1029,'13800000202','ENABLE'),
  (1103,'023030638','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','李成','branch_manager',1020,'13800000204','ENABLE'),
  (1105,'023032058','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','曾军锋','branch_manager',1017,'13800000206','ENABLE'),
  (1106,'023040001','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','吴和','branch_manager',1012,'13800000207','ENABLE'),
  (1107,'023005101','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','陈明','branch_manager',1028,'13800000208','ENABLE'),
  (1108,'023003206','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','曹杏妹','branch_manager',1031,'13800000209','ENABLE'),
  (1110,'023003965','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','蒋丽锋','branch_manager',1007,'13800000211','ENABLE'),
  (1111,'023003921','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','史晗晖','branch_manager',1009,'13800000212','ENABLE'),
  (1112,'023004771','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','梅韵渊','branch_manager',1014,'13800000213','ENABLE'),
  (1113,'023004869','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','李旭','branch_manager',1033,'13800000214','ENABLE'),
  (1114,'023005341','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','崔小斌','branch_manager',1019,'13800000215','ENABLE'),
  (1115,'023005931','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','谢小康','branch_manager',1018,'13800000216','ENABLE'),
  (1117,'023006951','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','王莺','branch_manager',1016,'13800000218','ENABLE'),
  (1118,'023007271','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','钱跃峰','branch_manager',1025,'13800000219','ENABLE'),
  (1119,'023007781','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','徐云','branch_manager',1030,'13800000220','ENABLE'),
  (1120,'023008021','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','陈雪','branch_manager',1032,'13800000221','ENABLE'),
  (1122,'023008251','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','董维','branch_manager',1010,'13800000223','ENABLE'),
  (1123,'023008761','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','王文娜','branch_manager',1023,'13800000224','ENABLE'),
  (1124,'023009601','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','王凯','branch_manager',1024,'13800000225','ENABLE')
ON DUPLICATE KEY UPDATE nick_name=VALUES(nick_name), org_id=VALUES(org_id), role_code=VALUES(role_code), status='ENABLE';

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
  (2009,'auditor','审计人员','授权档案/表决汇总/导出查询','1,5,11,12'),
  (2011,'secretary','贷审会秘书岗','贷审会秘书岗审核(需求四:≥1000万且利率<2.6%的必经审核,否决即拦截)','1,4,5,10,11')
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
  (12,1003,1000,'president','1'),
  (13,1004,1000,'admin','1'),
  (14,1013,1000,'config_reviewer','1'),
  (15,1014,1000,'auditor','1')
ON DUPLICATE KEY UPDATE is_default=VALUES(is_default);

-- ---------- 种子:真实支行行长岗位绑定(2026-08-14,20 位一支行一行长,org_id 为机构 id,对齐 09 迁移新 id) ----------
INSERT INTO `ccr_sys_user_post` (`id`,`user_id`,`org_id`,`post_code`,`is_default`) VALUES
  (100,1100,1026,'branch_manager','1'),
  (101,1101,1029,'branch_manager','1'),
  (103,1103,1020,'branch_manager','1'),
  (105,1105,1017,'branch_manager','1'),
  (106,1106,1012,'branch_manager','1'),
  (107,1107,1028,'branch_manager','1'),
  (108,1108,1031,'branch_manager','1'),
  (110,1110,1007,'branch_manager','1'),
  (111,1111,1009,'branch_manager','1'),
  (112,1112,1014,'branch_manager','1'),
  (113,1113,1033,'branch_manager','1'),
  (114,1114,1019,'branch_manager','1'),
  (115,1115,1018,'branch_manager','1'),
  (117,1117,1016,'branch_manager','1'),
  (118,1118,1025,'branch_manager','1'),
  (119,1119,1030,'branch_manager','1'),
  (120,1120,1032,'branch_manager','1'),
  (122,1122,1010,'branch_manager','1'),
  (123,1123,1023,'branch_manager','1'),
  (124,1124,1024,'branch_manager','1')
ON DUPLICATE KEY UPDATE is_default=VALUES(is_default);
-- ---------- 种子:真实客户经理(2026-08-14,来源数仓 dws.ccr_sys_user 客户经理用户信息表,33 位) ----------
-- 39 条源数据:5 条机构不在系统机构表(320223043沈程/320223040沈忱/320223037朱桓辉/320223011童心宇/320223031丁宁)
-- +1 条登录名截断(023012…中嘉远)未建号;登录名=工号,密码=统一初始密码 Yxnsh@1a3s(共享 BCrypt,首登强制改密);
-- org_id 为机构 id(对齐 09_system_dept 迁移后新 id)
INSERT INTO `ccr_sys_user` (`id`,`username`,`password`,`nick_name`,`role_code`,`org_id`,`phone`,`status`) VALUES
  (1200,'02301039','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','许欢','customer_manager',1032,'13800000301','ENABLE'),
  (1201,'02301046','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','冯立','customer_manager',1015,'13800000302','ENABLE'),
  (1202,'02301049','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','吴宇超','customer_manager',1018,'13800000303','ENABLE'),
  (1203,'02301050','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','樊超','customer_manager',1009,'13800000304','ENABLE'),
  (1204,'02301059','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','钱祉好','customer_manager',1003,'13800000305','ENABLE'),
  (1205,'02301071','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','万迪','customer_manager',1004,'13800000306','ENABLE'),
  (1206,'02301076','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','邓倩云','customer_manager',1009,'13800000307','ENABLE'),
  (1207,'02301082','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','王若玮','customer_manager',1019,'13800000308','ENABLE'),
  (1208,'02301100','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','蒋森','customer_manager',1019,'13800000309','ENABLE'),
  (1209,'02301120','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','樊文杰','customer_manager',1005,'13800000310','ENABLE'),
  (1210,'02301129','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','吴希清','customer_manager',1006,'13800000311','ENABLE'),
  (1211,'02301137','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','刘靓','customer_manager',1015,'13800000312','ENABLE'),
  (1212,'02301139','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','吴天石','customer_manager',1006,'13800000313','ENABLE'),
  (1213,'02301152','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','杨佳琪','customer_manager',1022,'13800000314','ENABLE'),
  (1214,'02301162','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','管欢欣','customer_manager',1020,'13800000315','ENABLE'),
  (1215,'02301177','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','董达','customer_manager',1011,'13800000316','ENABLE'),
  (1216,'02301178','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','胡威威','customer_manager',1003,'13800000317','ENABLE'),
  (1217,'02301180','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','王璐','customer_manager',1013,'13800000318','ENABLE'),
  (1218,'02301182','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','张熙','customer_manager',1006,'13800000319','ENABLE'),
  (1219,'02301187','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','杨赟禾','customer_manager',1011,'13800000320','ENABLE'),
  (1220,'02301193','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','马天阳','customer_manager',1006,'13800000321','ENABLE'),
  (1221,'02301195','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','胡潇','customer_manager',1012,'13800000322','ENABLE'),
  (1222,'02301200','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','陆文杰','customer_manager',1003,'13800000323','ENABLE'),
  (1223,'02301203','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','吴润舟','customer_manager',1014,'13800000324','ENABLE'),
  (1224,'02301209','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','狄龑','customer_manager',1029,'13800000325','ENABLE'),
  (1225,'02301210','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','薛翔月','customer_manager',1019,'13800000326','ENABLE'),
  (1226,'02301217','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','周敏','customer_manager',1024,'13800000327','ENABLE'),
  (1227,'02301220','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','王杰','customer_manager',1014,'13800000328','ENABLE'),
  (1228,'02301226','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','汤震敏','customer_manager',1001,'13800000329','ENABLE'),
  (1229,'02301231','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','蒋思铭','customer_manager',1016,'13800000330','ENABLE'),
  (1230,'02301234','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','陈旭昊','customer_manager',1021,'13800000331','ENABLE'),
  (1231,'02301239','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','吴志尧','customer_manager',1003,'13800000332','ENABLE'),
  (1232,'02301242','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','夏圣杰','customer_manager',1029,'13800000333','ENABLE')
ON DUPLICATE KEY UPDATE nick_name=VALUES(nick_name);
-- ---------- 种子:真实客户经理岗位绑定(33 位,org_id 为机构 id) ----------
INSERT INTO `ccr_sys_user_post` (`id`,`user_id`,`org_id`,`post_code`,`is_default`) VALUES
  (200,1200,1032,'customer_manager','1'),
  (201,1201,1015,'customer_manager','1'),
  (202,1202,1018,'customer_manager','1'),
  (203,1203,1009,'customer_manager','1'),
  (204,1204,1003,'customer_manager','1'),
  (205,1205,1004,'customer_manager','1'),
  (206,1206,1009,'customer_manager','1'),
  (207,1207,1019,'customer_manager','1'),
  (208,1208,1019,'customer_manager','1'),
  (209,1209,1005,'customer_manager','1'),
  (210,1210,1006,'customer_manager','1'),
  (211,1211,1015,'customer_manager','1'),
  (212,1212,1006,'customer_manager','1'),
  (213,1213,1022,'customer_manager','1'),
  (214,1214,1020,'customer_manager','1'),
  (215,1215,1011,'customer_manager','1'),
  (216,1216,1003,'customer_manager','1'),
  (217,1217,1013,'customer_manager','1'),
  (218,1218,1006,'customer_manager','1'),
  (219,1219,1011,'customer_manager','1'),
  (220,1220,1006,'customer_manager','1'),
  (221,1221,1012,'customer_manager','1'),
  (222,1222,1003,'customer_manager','1'),
  (223,1223,1014,'customer_manager','1'),
  (224,1224,1029,'customer_manager','1'),
  (225,1225,1019,'customer_manager','1'),
  (226,1226,1024,'customer_manager','1'),
  (227,1227,1014,'customer_manager','1'),
  (228,1228,1001,'customer_manager','1'),
  (229,1229,1016,'customer_manager','1'),
  (230,1230,1021,'customer_manager','1'),
  (231,1231,1003,'customer_manager','1'),
  (232,1232,1029,'customer_manager','1')
ON DUPLICATE KEY UPDATE is_default=VALUES(is_default);
-- ---------- 种子:真实部门总经理(2026-08-14,来源数仓 dept_gm 用户信息表,19 位) ----------
-- 机构匹配:名称匹配(HTML org_code 与机构表编码体系不同,弃用);张文伟->授信评审部(1050)、赵剑->零售金融部(1056) 为人工指定;
-- 登录名=工号,密码=统一初始密码 Yxnsh@1a3s(共享 BCrypt,首登强制改密);org_id 为机构 id
INSERT INTO `ccr_sys_user` (`id`,`username`,`password`,`nick_name`,`role_code`,`org_id`,`phone`,`email`,`status`) VALUES
  (2088225316330213378,'02301288','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','唐潮','dept_gm',1006,NULL,NULL,'ENABLE'),
  (2088225316330213379,'02300032','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','王晓愉','dept_gm',1053,NULL,NULL,'ENABLE'),
  (2088225316330213380,'02300040','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','程欣','dept_gm',1049,NULL,NULL,'ENABLE'),
  (2088225316330213381,'02300041','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','边琦','dept_gm',1036,NULL,NULL,'ENABLE'),
  (2088225316330213382,'02300042','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','林晖','dept_gm',1046,NULL,NULL,'ENABLE'),
  (2088225316330213383,'02300184','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','吴焘','dept_gm',1051,NULL,NULL,'ENABLE'),
  (2088225316330213384,'02300122','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','周鲲鹏','dept_gm',1041,NULL,NULL,'ENABLE'),
  (2088225316330213385,'02300771','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','丁玲','dept_gm',1055,NULL,NULL,'ENABLE'),
  (2088225316330213386,'02300222','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','周瑜','dept_gm',1044,NULL,NULL,'ENABLE'),
  (2088225316330213387,'02300233','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','袁科威','dept_gm',1042,NULL,NULL,'ENABLE'),
  (2088225316330213388,'02300325','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','王翼','dept_gm',1040,NULL,NULL,'ENABLE'),
  (2088225316330213389,'02300453','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','袁雪峰','dept_gm',1055,NULL,NULL,'ENABLE'),
  (2088225316330213390,'02300502','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','杨莉','dept_gm',1047,NULL,NULL,'ENABLE'),
  (2088225316330213391,'02300633','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','孙强','dept_gm',1034,NULL,NULL,'ENABLE'),
  (2088225316330213392,'02300648','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','张力','dept_gm',1039,NULL,NULL,'ENABLE'),
  (2088225316330213393,'02300649','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','蒋丹','dept_gm',1052,NULL,NULL,'ENABLE'),
  (2088225316330213394,'02301018','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','任晨曦','dept_gm',1048,NULL,NULL,'ENABLE'),
  (2088225316330213395,'02300273','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','张文伟','dept_gm',1050,NULL,NULL,'ENABLE'),
  (2088225316330213396,'02301435','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi','赵剑','dept_gm',1056,NULL,NULL,'ENABLE')
ON DUPLICATE KEY UPDATE nick_name=VALUES(nick_name);
-- ---------- 种子:真实部门总经理岗位绑定(19 位) ----------
INSERT INTO `ccr_sys_user_post` (`id`,`user_id`,`org_id`,`post_code`,`is_default`) VALUES
  (2088227559681687572,2088225316330213378,1006,'dept_gm','1'),
  (2088227559681687573,2088225316330213379,1053,'dept_gm','1'),
  (2088227559681687574,2088225316330213380,1049,'dept_gm','1'),
  (2088227559681687575,2088225316330213381,1036,'dept_gm','1'),
  (2088227559681687576,2088225316330213382,1046,'dept_gm','1'),
  (2088227559681687577,2088225316330213383,1051,'dept_gm','1'),
  (2088227559681687578,2088225316330213384,1041,'dept_gm','1'),
  (2088227559681687579,2088225316330213385,1055,'dept_gm','1'),
  (2088227559681687580,2088225316330213386,1044,'dept_gm','1'),
  (2088227559681687581,2088225316330213387,1042,'dept_gm','1'),
  (2088227559681687582,2088225316330213388,1040,'dept_gm','1'),
  (2088227559681687583,2088225316330213389,1055,'dept_gm','1'),
  (2088227559681687584,2088225316330213390,1047,'dept_gm','1'),
  (2088227559681687585,2088225316330213391,1034,'dept_gm','1'),
  (2088227559681687586,2088225316330213392,1039,'dept_gm','1'),
  (2088227559681687587,2088225316330213393,1052,'dept_gm','1'),
  (2088227559681687588,2088225316330213394,1048,'dept_gm','1'),
  (2088227559681687589,2088225316330213395,1050,'dept_gm','1'),
  (2088227559681687590,2088225316330213396,1056,'dept_gm','1')
ON DUPLICATE KEY UPDATE is_default=VALUES(is_default);
