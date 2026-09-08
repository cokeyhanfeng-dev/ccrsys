-- ============================================================
-- 决议书查询专岗(resolution_query,2026-09-08)
-- 需求:部门按 客户名称/客户号(兼集团号)/决议书编号 查询全部「当前有效决议」并下载决议书 PDF。
-- 口径:全量可见但仅列有效执行状态决议(执行状态白名单,JOIN 天然排除被否决单);
--      该角色仅决议书查询与下载,无档案 JSON 查看权(档案接口对该角色保持 403)。
-- 幂等写法仿 18_contract_operator.sql,可重复执行。
-- ============================================================

USE `ccr_rate`;

-- 菜单(id=14,现有最大 13):决议书查询
INSERT INTO `ccr_sys_menu`
  (`id`,`parent_id`,`menu_name`,`path`,`perms`,`sort_no`)
VALUES
  (14,0,'决议书查询','/resolution','ccr:resolution',14)
ON DUPLICATE KEY UPDATE
  menu_name=VALUES(menu_name), path=VALUES(path), perms=VALUES(perms), sort_no=VALUES(sort_no);

-- 角色(id=2012,现有最大 2011):决议书查询员
INSERT INTO `ccr_sys_role`
  (`id`,`role_code`,`role_name`,`remark`,`menu_ids`)
VALUES
  (2012,'resolution_query','决议书查询员','决议书查询与下载(仅当前有效决议,无档案权限)','1,14')
ON DUPLICATE KEY UPDATE
  role_name=VALUES(role_name), remark=VALUES(remark), menu_ids=VALUES(menu_ids);

-- admin 菜单追加 14(与前端守卫 admin 特判保持一致;幂等)
UPDATE `ccr_sys_role` SET `menu_ids` = CONCAT(`menu_ids`, ',14')
WHERE `role_code` = 'admin' AND FIND_IN_SET('14', `menu_ids`) = 0;

-- 隔离测试账号,密码=统一初始密码 Yxnsh@1a3s(SSO 接入后走统一认证验密,本地 fallback);
-- 生产环境必须通过正式用户管理流程创建并修改密码。
INSERT INTO `ccr_sys_user`
  (`id`,`username`,`password`,`nick_name`,`role_code`,`org_id`,`phone`,`status`)
VALUES
  (1017,'resq','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi',
   '郑决议查询','resolution_query',1000,'13800000226','ENABLE')
ON DUPLICATE KEY UPDATE
  nick_name=VALUES(nick_name), role_code=VALUES(role_code), org_id=VALUES(org_id),
  phone=VALUES(phone), status=VALUES(status);

INSERT INTO `ccr_sys_user_post`
  (`id`,`user_id`,`org_id`,`post_code`,`is_default`)
VALUES
  (17,1017,1000,'resolution_query','1')
ON DUPLICATE KEY UPDATE is_default=VALUES(is_default);
