-- ============================================================
-- 手工测试业务数据清理(2026-08-13,配合 docs/06_测试方案.md §12 数据准备 / docs/07_手工测试用例.md)
-- 范围:清空全部业务表(申请/分项/审批/表决/决议/快照/承诺/通知/审计/导出/outbox/变更日志)
--   + Warm-Flow 运行数据(flow_instance/flow_task/flow_his_task/flow_skip,审批实例/任务/历史/跳过)
-- 保留:系统主数据(sys_user/sys_dept/sys_menu/sys_role/sys_user_post)、配置表
--   (dict/rate_matrix/node_permission/node_assignee/dept_vp/product*/lpr*/rule*/cache_config/
--    dataset*/field*/metric*/source_mapping/validation_rule/display_schema/notification_rule*/
--    tracking_policy*)、数仓 mock(caps_*/dw_*,由 10_mock/15/16/17 维护)、
--    Warm-Flow 流程定义(flow_definition/flow_node/flow_user,审批流程定义必须保留)
-- 幂等:可重复执行;TRUNCATE 隐式提交,执行前已 SET FOREIGN_KEY_CHECKS=0
-- 执行:docker exec -i ccr-mysql mysql -uroot -proot123 --default-character-set=utf8mb4 ccr_rate < db/20_clean_biz.sql
-- ============================================================

USE `ccr_rate`;

SET FOREIGN_KEY_CHECKS = 0;

-- Warm-Flow 运行数据(流程定义 flow_definition/flow_node/flow_user 保留)
TRUNCATE TABLE flow_skip;
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

SET FOREIGN_KEY_CHECKS = 1;
