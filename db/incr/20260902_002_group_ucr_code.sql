-- ============================================================
-- 增量 002(2026-09-02):集团主数据快照补统一社会信用代码列
-- 需求:申请页集团区块「统一社会信用代码」录入时无法带出(bug)。
--   根因:集团本身无 USCC 数据源——dw_customer_group_snapshot 自 V1.0
--   建表(docs/15 B01)从未含证件列,手工集团表 ccr_group 亦无;
--   USCC 只存在于成员对公主档(caps_corp_cust_basic_info.cert_no)。
--   拍板:数仓集团主表新增 ucr_code 列,由数仓采集集团登记证号推送。
--   后端 findGroup 为 SELECT * 自动带出,前端 loan.vue queryGroup
--   「数仓有则带出」逻辑已就绪,加列后零代码改动即可带出。
-- 类型:varchar(64) 与成员对公主档 cert_no 同口径,便于与核心成员一致。
-- 同步:docs/15_数仓数据需求清单.md B01 需补该字段(数仓侧按新结构推送)。
-- 幂等:information_schema 检查列存在,不存在才 ALTER,可重复执行。
-- 执行:docker exec -i ccr-mysql mysql -uroot -proot123 < db/incr/20260902_002_group_ucr_code.sql
-- ============================================================

USE `ccr_rate`;

DROP PROCEDURE IF EXISTS `ccr_add_group_ucr_code_dw`;
DELIMITER $$
CREATE PROCEDURE `ccr_add_group_ucr_code_dw`()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA='ccr_rate' AND TABLE_NAME='dw_customer_group_snapshot'
                   AND COLUMN_NAME='ucr_code') THEN
    ALTER TABLE `dw_customer_group_snapshot`
      ADD COLUMN `ucr_code` VARCHAR(64) NULL
      COMMENT '统一社会信用代码(集团登记证号,数仓采集;2026-09-02)';
  END IF;
END$$
DELIMITER ;
CALL `ccr_add_group_ucr_code_dw`();
DROP PROCEDURE `ccr_add_group_ucr_code_dw`;
