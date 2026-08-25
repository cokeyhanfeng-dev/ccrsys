-- ============================================================
-- 增量 004(2026-08-25):关联人证件类型列
-- 背景:关联人按证件号反查数仓需区分对公/对私,实体 CcrApplicationRelatedPerson
--   已含 certType 字段(前端 buildPayload/落库/详情带出均引用),本地测试库缺列
--   导致 GET 申请详情 Unknown column 'cert_type'。
-- 幂等:information_schema 检查列存在,可重复执行(仿 24_plaintext_sensitive.sql)。
-- ============================================================

USE `ccr_rate`;

DROP PROCEDURE IF EXISTS `ccr_related_person_cert_type`;
DELIMITER $$
CREATE PROCEDURE `ccr_related_person_cert_type`()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA='ccr_rate' AND TABLE_NAME='ccr_application_related_person'
                   AND COLUMN_NAME='cert_type') THEN
    ALTER TABLE `ccr_application_related_person`
      ADD COLUMN `cert_type` VARCHAR(16) NULL COMMENT '证件类型(USCC对公/ID_CARD对私)' AFTER `cert_no`;
  END IF;
END$$
DELIMITER ;
CALL `ccr_related_person_cert_type`();
DROP PROCEDURE `ccr_related_person_cert_type`;
