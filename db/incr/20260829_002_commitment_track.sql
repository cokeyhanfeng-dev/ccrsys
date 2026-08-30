-- ============================================================
-- 增量 002(2026-08-29):承诺跟踪简化改造(v2·无定时任务版)——承诺跟踪表 + 目标类型/跟踪状态字典
-- 需求:docs/28。旧体系(承诺计划+每日定时评估+预警策略)停用保留只读;新体系一张跟踪表三种读法:
--   当前完成度=查询时实时算(数仓最新批次÷目标,不落库);到期惰性归档(读前 settleExpired,
--   按 data_dt<=end_date 最近批次定案);机构达成率=聚合终态行。旧 7 表(plan/metric/member_alloc/
--   evaluation/policy/policy_version/threshold)不迁移,保留只读。
-- 幂等:CREATE TABLE IF NOT EXISTS + INSERT IGNORE(唯一键),可重复执行。
-- 执行:docker exec -i ccr-mysql mysql -uroot -proot123 --default-character-set=utf8mb4 < db/incr/20260829_002_commitment_track.sql
-- ============================================================

USE `ccr_rate`;

-- ---------- 承诺跟踪表(在途行 + 终态行即历史完成情况表) ----------
-- status 语义:v2 仅 TRACKING 跟踪中 / FINISHED_MET 已完成 / FINISHED_UNMET 未完成
--   (BaseEntity 默认 ACTIVE 由业务显式 set 覆盖,不依赖 MetaObjectHandler 兜底)
-- org_id 取 ccr_application.applicant_org_id,manager_id 取 applicant_user_id(异步 Outbox 消费必须显式 set,
--   不能靠 MetaObjectHandler 从 session 兜底成 0)
CREATE TABLE IF NOT EXISTS `ccr_commitment_track` (
  `id`                  BIGINT       NOT NULL,
  `tenant_id`           VARCHAR(20)  NOT NULL DEFAULT '000000',
  `business_no`         VARCHAR(64)  NOT NULL,
  `org_id`              BIGINT       NOT NULL COMMENT '机构(取申请人机构 applicant_org_id)',
  `status`              VARCHAR(20)  NOT NULL DEFAULT 'TRACKING' COMMENT 'TRACKING跟踪中/FINISHED_MET已完成/FINISHED_UNMET未完成',
  `version_no`          INT          NOT NULL DEFAULT 1,
  `create_dept`         BIGINT       NULL,
  `create_by`           BIGINT       NOT NULL,
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`           BIGINT       NULL,
  `update_time`         DATETIME     NULL,
  `del_flag`            CHAR(1)      NOT NULL DEFAULT '0',
  `track_no`            VARCHAR(32)  NOT NULL COMMENT '跟踪编号(TRK+yyyyMMdd+序号)',
  `application_id`      BIGINT       NOT NULL COMMENT '来源申请主键',
  `application_no`      VARCHAR(32)  NULL COMMENT '来源申请编号',
  `customer_no`         VARCHAR(64)  NULL COMMENT '客户号(申请主客户)',
  `member_customer_no`  VARCHAR(64)  NULL COMMENT '成员客户号(集团成员承诺)',
  `manager_id`          BIGINT       NULL COMMENT '客户经理(取申请人 applicant_user_id)',
  `metric_code`         VARCHAR(40)  NOT NULL COMMENT '稳定指标编码',
  `metric_name`         VARCHAR(100) NULL COMMENT '指标名称',
  `target_kind`         VARCHAR(16)  NOT NULL COMMENT '目标类型:BALANCE目标余额/COUNT笔数/RATIO比例(仅此三种)',
  `target_value`        DECIMAL(20,4) NULL COMMENT '申请时需要达成的目标',
  `unit`                VARCHAR(16)  NULL COMMENT '单位(随目标类型联动:万元/笔/%)',
  `end_date`            DATE         NOT NULL COMMENT '承诺截止日期(取 ccr_application_commitment.end_date,唯一时间基准)',
  `final_actual`        DECIMAL(20,4) NULL COMMENT '定案时指标当前值(终态写入;截止日前无批次为 NULL)',
  `final_ratio`         DECIMAL(9,4)  NULL COMMENT '定案完成比例(终态写入;final_ratio>=1 完成)',
  `final_data_dt`       DATE         NULL COMMENT '定案所用数仓批次日期(无批次为 NULL)',
  `finish_time`         DATETIME     NULL COMMENT '归档时间',
  `remark`              VARCHAR(255) NULL COMMENT '备注(如"数仓无数据")',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_track` (`application_id`,`metric_code`,`member_customer_no`),
  KEY `idx_track_no` (`track_no`),
  KEY `idx_track_status` (`status`),
  KEY `idx_track_org` (`org_id`),
  KEY `idx_track_manager` (`manager_id`)
) ENGINE=InnoDB COMMENT='ccr_commitment_track 承诺跟踪表(在途行+终态行即历史完成情况,零定时任务)';

-- ---------- 字典:承诺目标类型(收敛为三种) ----------
INSERT IGNORE INTO `ccr_dict_type` (`id`,`dict_code`,`dict_name`) VALUES
  (18,'ccr_commitment_target_kind','承诺目标类型');
INSERT IGNORE INTO `ccr_dict_item` (`id`,`dict_code`,`item_code`,`item_name`,`sort_no`) VALUES
  (266,'ccr_commitment_target_kind','BALANCE','目标余额',1),
  (267,'ccr_commitment_target_kind','COUNT','笔数',2),
  (268,'ccr_commitment_target_kind','RATIO','比例',3);

-- ---------- 字典:承诺跟踪状态(v2 仅三种) ----------
INSERT IGNORE INTO `ccr_dict_type` (`id`,`dict_code`,`dict_name`) VALUES
  (19,'ccr_commitment_track_status','承诺跟踪状态');
INSERT IGNORE INTO `ccr_dict_item` (`id`,`dict_code`,`item_code`,`item_name`,`sort_no`) VALUES
  (269,'ccr_commitment_track_status','TRACKING','跟踪中',1),
  (270,'ccr_commitment_track_status','FINISHED_MET','已完成',2),
  (271,'ccr_commitment_track_status','FINISHED_UNMET','未完成',3);
