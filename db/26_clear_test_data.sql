-- ============================================================
-- 清除测试数据(2026-08-20,配合交付验收 / 回归前清理)
-- 范围:
--   ① 业务表:全部 TRUNCATE(申请/分项/担保/审批/表决/决议/快照/承诺/通知/审计/导出/outbox/
--      变更日志/手工集团) + Warm-Flow 运行数据(flow_instance/task/his_task/flow_user)
--      + Warm-Flow 流程定义(flow_definition/flow_node/flow_skip,后端启动自动重建)
--   ② 数仓测试虚拟客户:按客户编号 DELETE(caps_*/dw_* 中 CUST001-004/101、CUSTP001/002、
--      MEMBER_A/B、REL001、GROUP001 等测试编号)
-- 保留:
--   - 系统主数据(ccr_sys_user/ccr_sys_dept/ccr_sys_menu/ccr_sys_role/ccr_sys_user_post)
--   - 配置表(dict/rate_matrix/rate_rule/rate_rule_set/node_permission/node_assignee/
--     dept_vp/product*/lpr*/rule*/cache_config/dataset*/field*/metric_definition/
--     source_mapping/validation_rule/display_schema/notification_rule*/tracking_policy*)
--   - 数仓真实机构维度 dw_org_dim / dw_org_performance_snapshot(宜兴农商行真实机构,保留)
--   - (Warm-Flow 流程定义不保留:flow_definition/flow_node/flow_skip 一并清理,
--     后端启动 StandardFlowInitializer→ensureFlow 检测无发布定义→createFlow 自动重建)
-- 幂等:业务表 TRUNCATE 可重复执行;数仓 DELETE 按测试编号精确删除,重复执行结果一致。
-- 安全:TRUNCATE 隐式提交,执行前已 SET FOREIGN_KEY_CHECKS=0。
-- 执行:docker exec -i ccr-mysql mysql -uroot -proot123 --default-character-set=utf8mb4 ccr_rate < db/26_clear_test_data.sql
-- 验证:执行后 SELECT COUNT(*) 对比「验证要点」;系统主数据与机构维度行数不变。
-- ============================================================

USE `ccr_rate`;

-- ══════════ 一、业务表清理(TRUNCATE) ══════════

SET FOREIGN_KEY_CHECKS = 0;

-- Warm-Flow 流程定义(flow_skip 属定义非运行数据;definition/node/skip 三表一体,
-- 不可单独清 skip,否则定义残缺且后端不重建;清后重启自动重建)
TRUNCATE TABLE flow_skip;
TRUNCATE TABLE flow_node;
TRUNCATE TABLE flow_definition;

-- Warm-Flow 运行数据(运行时办理人/实例/任务;定义已清,在途实例须一并清空)
TRUNCATE TABLE flow_user;
TRUNCATE TABLE flow_task;
TRUNCATE TABLE flow_his_task;
TRUNCATE TABLE flow_instance;

-- 表决与行长决策
TRUNCATE TABLE ccr_vote_round_item;
TRUNCATE TABLE ccr_vote_assignment;
TRUNCATE TABLE ccr_vote_result;
TRUNCATE TABLE ccr_vote_round;
TRUNCATE TABLE ccr_ballot;
TRUNCATE TABLE ccr_president_decision;

-- 决议与承诺跟踪
TRUNCATE TABLE ccr_resolution_execution;
TRUNCATE TABLE ccr_resolution;
TRUNCATE TABLE ccr_commitment_metric;
TRUNCATE TABLE ccr_commitment_member_alloc;
TRUNCATE TABLE ccr_commitment_plan;
TRUNCATE TABLE ccr_commitment_track;
TRUNCATE TABLE ccr_tracking_evaluation;

-- 申请/分项/担保/合同/附件
TRUNCATE TABLE ccr_application_attachment;
TRUNCATE TABLE ccr_application_related_person;
TRUNCATE TABLE ccr_application_other_loan;
TRUNCATE TABLE ccr_application_relation;
TRUNCATE TABLE ccr_application_commitment;
TRUNCATE TABLE ccr_application_member;
TRUNCATE TABLE ccr_guarantee_measure;
TRUNCATE TABLE ccr_guarantee_package;
TRUNCATE TABLE ccr_note_guarantee_rel;
TRUNCATE TABLE ccr_pricing_item_contract_rel;
TRUNCATE TABLE ccr_pricing_item_deposit_rel;
TRUNCATE TABLE ccr_attachment;
TRUNCATE TABLE ccr_rate_adjustment;
TRUNCATE TABLE ccr_approval_action;
TRUNCATE TABLE ccr_pricing_item;
TRUNCATE TABLE ccr_application;

-- 快照
TRUNCATE TABLE ccr_snapshot_quality_result;
TRUNCATE TABLE ccr_snapshot_relation;
TRUNCATE TABLE ccr_snapshot_record;
TRUNCATE TABLE ccr_snapshot_bundle;

-- 通知/审计/导出/变更日志/outbox
TRUNCATE TABLE ccr_notification_log;
TRUNCATE TABLE ccr_audit_log;
TRUNCATE TABLE ccr_export_record;
TRUNCATE TABLE ccr_config_change_log;
TRUNCATE TABLE ccr_outbox_event;

-- 手工集团(012 新增,20_clean_biz 未覆盖,补充)
TRUNCATE TABLE ccr_group_member;
TRUNCATE TABLE ccr_group;
-- 通知接收人/关系(20_clean_biz 未覆盖,补充;0 行亦幂等)
TRUNCATE TABLE ccr_notification_recipient;
TRUNCATE TABLE ccr_relation;

SET FOREIGN_KEY_CHECKS = 1;

-- ══════════ 二、数仓测试虚拟客户清理(DELETE,保留真实机构维度) ══════════

-- 测试客户编号集合(10_mock / 15_test_data / 17_test_data_latest / 22_test_data_multi_item 引入)
--   CUST001/CUST002/CUST003/CUST004(对公) CUST101/CUSTP001/CUSTP002(个人)
--   MEMBER_A/MEMBER_B(集团成员) REL001(关联人) GROUP001(测试集团)

-- 客户主数据(caps_*)
DELETE FROM caps_corp_cust_basic_info WHERE cust_no IN
  ('CUST001','CUST002','CUST003','CUST004','CUST101','CUSTP001','CUSTP002','MEMBER_A','MEMBER_B','REL001');
DELETE FROM caps_indv_cust_basic_info WHERE cust_no IN
  ('CUST001','CUST002','CUST003','CUST004','CUST101','CUSTP001','CUSTP002','MEMBER_A','MEMBER_B','REL001');

-- 贡献度 / 征信 / 担保 / 抵押 / 融资(按 cust_no)
DELETE FROM dw_contribution_metric WHERE cust_no IN
  ('CUST001','CUST002','CUST003','CUST004','CUST101','CUSTP001','CUSTP002','MEMBER_A','MEMBER_B','REL001','GROUP001');
DELETE FROM dw_credit_report_snapshot WHERE cust_no IN
  ('CUST001','CUST002','CUST003','CUST004','CUST101','CUSTP001','CUSTP002','MEMBER_A','MEMBER_B','REL001');
DELETE FROM dw_guarantor_snapshot WHERE cust_no IN
  ('CUST001','CUST002','CUST003','CUST004','CUST101','CUSTP001','CUSTP002','MEMBER_A','MEMBER_B','REL001');
DELETE FROM dw_mortgage_snapshot WHERE cust_no IN
  ('CUST001','CUST002','CUST003','CUST004','CUST101','CUSTP001','CUSTP002','MEMBER_A','MEMBER_B','REL001');
DELETE FROM dw_credit_financing_summary WHERE cust_no IN
  ('CUST001','CUST002','CUST003','CUST004','CUST101','CUSTP001','CUSTP002','MEMBER_A','MEMBER_B','REL001');
DELETE FROM dw_credit_financing_detail WHERE customer_no IN
  ('CUST001','CUST002','CUST003','CUST004','CUST101','CUSTP001','CUSTP002','MEMBER_A','MEMBER_B','REL001');

-- 授信协议 / 存款账户(按 customer_no)
DELETE FROM dw_credit_agreement_snapshot WHERE customer_no IN
  ('CUST001','CUST002','CUST003','CUST004','CUST101','CUSTP001','CUSTP002','MEMBER_A','MEMBER_B','REL001');
DELETE FROM dw_deposit_account_snapshot WHERE customer_no IN
  ('CUST001','CUST002','CUST003','CUST004','CUST101','CUSTP001','CUSTP002','MEMBER_A','MEMBER_B','REL001');

-- 贷款合同 / 借据(按 borrower_customer_no)
DELETE FROM dw_loan_contract_snapshot WHERE borrower_customer_no IN
  ('CUST001','CUST002','CUST003','CUST004','CUST101','CUSTP001','CUSTP002','MEMBER_A','MEMBER_B','REL001');
DELETE FROM dw_loan_note_snapshot WHERE borrower_customer_no IN
  ('CUST001','CUST002','CUST003','CUST004','CUST101','CUSTP001','CUSTP002','MEMBER_A','MEMBER_B','REL001');

-- 关联人(customer_no 或 related_customer_no 命中即删)
DELETE FROM dw_customer_relation_snapshot WHERE customer_no IN
  ('CUST001','CUST002','CUST003','CUST004','CUST101','CUSTP001','CUSTP002','MEMBER_A','MEMBER_B','REL001')
   OR related_customer_no IN
  ('CUST001','CUST002','CUST003','CUST004','CUST101','CUSTP001','CUSTP002','MEMBER_A','MEMBER_B','REL001');

-- 集团/成员授信(按 group_no / member_customer_no)
DELETE FROM dw_customer_group_snapshot WHERE group_no IN ('GROUP001');
DELETE FROM dw_customer_group_member_snapshot WHERE group_no IN ('GROUP001') OR member_customer_no IN
  ('MEMBER_A','MEMBER_B','CUST001','CUST002','CUST003','CUST004');
DELETE FROM dw_group_credit_snapshot WHERE group_no IN ('GROUP001');
DELETE FROM dw_member_credit_limit_snapshot WHERE member_customer_no IN ('MEMBER_A','MEMBER_B');
DELETE FROM dw_credit_tranche_snapshot WHERE member_customer_no IN ('MEMBER_A','MEMBER_B');

-- 注:dw_org_dim / dw_org_performance_snapshot 为真实机构维度,不在清理范围。

-- ══════════ 三、验证要点(执行后自查) ══════════

-- 1) 业务表全部为 0:
--    SELECT COUNT(*) FROM ccr_application;        -- 期望 0
--    SELECT COUNT(*) FROM ccr_ballot;             -- 期望 0
--    SELECT COUNT(*) FROM ccr_group;              -- 期望 0
--    SELECT COUNT(*) FROM ccr_audit_log;          -- 期望 0
-- 2) 数仓虚拟客户已删:
--    SELECT COUNT(*) FROM caps_corp_cust_basic_info;   -- 期望 0(全为测试客户)
--    SELECT COUNT(*) FROM dw_contribution_metric;      -- 期望 0(全为测试编号)
-- 3) 保留不变:
--    SELECT COUNT(*) FROM dw_org_dim;                  -- 期望 9(真实机构)
--    SELECT COUNT(*) FROM dw_org_performance_snapshot; -- 期望 3(真实机构绩效)
--    SELECT COUNT(*) FROM ccr_sys_user;                -- 期望 91(系统用户)
--    SELECT COUNT(*) FROM ccr_sys_dept;                -- 期望 55(真实机构)
--    SELECT COUNT(*) FROM ccr_rate_matrix;             -- 期望 90(权限矩阵配置)
