-- ============================================================
-- 合同经办岗：决议合同回填与执行核验专岗
-- ============================================================

USE `ccr_rate`;

-- 隔离测试账号，密码=统一初始密码 Yxnsh@1a3s（首登强制改密）；生产环境必须通过正式用户管理流程创建并修改密码。
INSERT INTO `ccr_sys_user`
  (`id`,`username`,`password`,`nick_name`,`role_code`,`org_id`,`phone`,`status`)
VALUES
  (1016,'contractor','$2a$10$F7xXIVTj0Q3EcSuo1S.CzeHutuc9MP2KDoPbxvYEFpfjX9UvJNPwi',
   '郑合同经办','contract_operator',1000,'13800000024','ENABLE')
ON DUPLICATE KEY UPDATE
  nick_name=VALUES(nick_name), role_code=VALUES(role_code), org_id=VALUES(org_id),
  phone=VALUES(phone), status=VALUES(status);

INSERT INTO `ccr_sys_role`
  (`id`,`role_code`,`role_name`,`remark`,`menu_ids`)
VALUES
  (2010,'contract_operator','合同经办岗','决议合同回填与执行核验','1,5')
ON DUPLICATE KEY UPDATE
  role_name=VALUES(role_name), remark=VALUES(remark), menu_ids=VALUES(menu_ids);

INSERT INTO `ccr_sys_user_post`
  (`id`,`user_id`,`org_id`,`post_code`,`is_default`)
VALUES
  (16,1016,1000,'contract_operator','1')
ON DUPLICATE KEY UPDATE is_default=VALUES(is_default);

-- 为旧申请补齐申请机构编码，确保分行级对象权限在升级后立即生效。
UPDATE `ccr_application` a
JOIN `ccr_sys_user` u ON u.id = a.applicant_user_id
JOIN `ccr_sys_dept` d ON d.id = u.org_id
SET a.apply_branch_code = d.branch_code,
    a.update_time = NOW()
WHERE (a.apply_branch_code IS NULL OR a.apply_branch_code = '')
  AND d.branch_code IS NOT NULL
  AND d.branch_code <> '';
