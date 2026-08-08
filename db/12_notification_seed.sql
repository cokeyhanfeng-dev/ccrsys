-- ============================================================
-- 客户贡献度与利率决策系统 · 通知规则/接收人种子(§11.4/§11.6)
-- 内容:ccr_notification_rule 三等级规则(AT_RISK/EXPIRED/WATCH)
--      + ccr_notification_recipient 动态接收人(沿链路/机构/角色解析)
-- 背景:03d_commitment 仅建表无种子,notifyEvaluation 因无 ACTIVE 规则直接返回空,
--      评估触发通知(达成风险/到期未达成/临近到期)无法外发
-- 触发等级口径(CommitmentServiceImpl.notifyEvaluations):
--      resultStatus=AT_RISK→AT_RISK / EXPIRED_UNMET→EXPIRED / riskLevel=WATCH→WATCH
-- 接收人解析(NotificationServiceImpl + RecipientResolver 各实现):
--      CUSTOMER_MANAGER 沿 计划→决议→分项→申请 解析申请人;
--      BRANCH_MANAGER 按计划归属机构 org_id 查 branch_manager 用户;
--      DEPT_GM/VICE_PRESIDENT 类角色默认编码 dept_gm/vice_president 全行查
-- 升级路径(§11.6):trigger 匹配规则后按 upgrade_rule_json[trigger] 追加接收人类型
-- 说明:固定 id + ON DUPLICATE KEY 幂等,可重复执行
-- ============================================================

USE `ccr_rate`;

-- ---------- 通知规则 ----------
INSERT INTO `ccr_notification_rule`
  (`id`,`tenant_id`,`business_no`,`org_id`,`status`,`version_no`,`del_flag`,`create_by`,
   `rule_no`,`rule_name`,`trigger_level`,`channel`,`repeat_interval_hours`,`max_repeat_count`,
   `cool_down_hours`,`upgrade_rule_json`,`message_template`) VALUES
  (9001,'000000','RULE_SEED_AT_RISK',1000,'ACTIVE',1,'0',1004,
   'RULE_AT_RISK','承诺达成风险预警','AT_RISK','SYSTEM',24,3,24,
   '{"AT_RISK":["DEPT_GM"]}',
   '承诺计划{planNo}(客户{customerNo})在{dataDt}评估达成率{achievementRatio}%,已达风险线({riskLevel}),请及时跟踪。'),
  (9005,'000000','RULE_SEED_EXPIRED',1000,'ACTIVE',1,'0',1004,
   'RULE_EXPIRED','承诺到期未达成预警','EXPIRED','SYSTEM',48,2,48,
   '{"EXPIRED":["VICE_PRESIDENT"]}',
   '承诺计划{planNo}(客户{customerNo})到期未达成({resultStatus}),达成率{achievementRatio}%,请核查并处理。'),
  (9010,'000000','RULE_SEED_WATCH',1000,'ACTIVE',1,'0',1004,
   'RULE_WATCH','承诺临近到期提醒','WATCH','SYSTEM',24,1,24,
   NULL,
   '承诺计划{planNo}(客户{customerNo})临近到期,当前{resultStatus},达成率{achievementRatio}%,请关注。')
ON DUPLICATE KEY UPDATE
  rule_name=VALUES(rule_name), status=VALUES(status), channel=VALUES(channel),
  max_repeat_count=VALUES(max_repeat_count), cool_down_hours=VALUES(cool_down_hours),
  upgrade_rule_json=VALUES(upgrade_rule_json), message_template=VALUES(message_template);

-- ---------- 接收人规则 ----------
-- AT_RISK:客户经理 + 支行行长(基础),部门总经理由升级路径 upgrade_rule_json 追加
INSERT INTO `ccr_notification_recipient`
  (`id`,`tenant_id`,`business_no`,`org_id`,`status`,`version_no`,`del_flag`,`create_by`,
   `rule_id`,`recipient_type`,`recipient_value`,`level_condition`) VALUES
  (9002,'000000','RULE_SEED_AT_RISK_R1',1000,'ACTIVE',1,'0',1004,9001,'CUSTOMER_MANAGER',NULL,NULL),
  (9003,'000000','RULE_SEED_AT_RISK_R2',1000,'ACTIVE',1,'0',1004,9001,'BRANCH_MANAGER',NULL,NULL)
ON DUPLICATE KEY UPDATE
  recipient_type=VALUES(recipient_type), recipient_value=VALUES(recipient_value),
  level_condition=VALUES(level_condition);
-- EXPIRED:客户经理 + 支行行长 + 部门总经理(基础),升级追加分管副行长
INSERT INTO `ccr_notification_recipient`
  (`id`,`tenant_id`,`business_no`,`org_id`,`status`,`version_no`,`del_flag`,`create_by`,
   `rule_id`,`recipient_type`,`recipient_value`,`level_condition`) VALUES
  (9006,'000000','RULE_SEED_EXPIRED_R1',1000,'ACTIVE',1,'0',1004,9005,'CUSTOMER_MANAGER',NULL,NULL),
  (9007,'000000','RULE_SEED_EXPIRED_R2',1000,'ACTIVE',1,'0',1004,9005,'BRANCH_MANAGER',NULL,NULL),
  (9008,'000000','RULE_SEED_EXPIRED_R3',1000,'ACTIVE',1,'0',1004,9005,'DEPT_GM',NULL,NULL),
  (9009,'000000','RULE_SEED_EXPIRED_R4',1000,'ACTIVE',1,'0',1004,9005,'VICE_PRESIDENT',NULL,NULL)
ON DUPLICATE KEY UPDATE
  recipient_type=VALUES(recipient_type), recipient_value=VALUES(recipient_value),
  level_condition=VALUES(level_condition);
-- WATCH:客户经理(基础,临近到期轻提醒)
INSERT INTO `ccr_notification_recipient`
  (`id`,`tenant_id`,`business_no`,`org_id`,`status`,`version_no`,`del_flag`,`create_by`,
   `rule_id`,`recipient_type`,`recipient_value`,`level_condition`) VALUES
  (9011,'000000','RULE_SEED_WATCH_R1',1000,'ACTIVE',1,'0',1004,9010,'CUSTOMER_MANAGER',NULL,NULL)
ON DUPLICATE KEY UPDATE
  recipient_type=VALUES(recipient_type), recipient_value=VALUES(recipient_value),
  level_condition=VALUES(level_condition);
