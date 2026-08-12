-- ============================================================
-- 申请提交幂等：同一申请最多一个快照包
-- ============================================================

USE `ccr_rate`;

DROP PROCEDURE IF EXISTS `ccr_add_snapshot_application_unique_key`;
DELIMITER $$
CREATE PROCEDURE `ccr_add_snapshot_application_unique_key`()
BEGIN
  DECLARE duplicate_application_count BIGINT DEFAULT 0;

  SELECT COUNT(*) INTO duplicate_application_count
  FROM (
    SELECT `application_id`
    FROM `ccr_snapshot_bundle`
    GROUP BY `application_id`
    HAVING COUNT(*) > 1
  ) duplicated;

  IF duplicate_application_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'ccr_snapshot_bundle 存在同申请多快照包，请先完成数据核查和迁移';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'ccr_rate'
      AND TABLE_NAME = 'ccr_snapshot_bundle'
      AND INDEX_NAME = 'uk_snapshot_application'
  ) THEN
    ALTER TABLE `ccr_snapshot_bundle`
      ADD UNIQUE KEY `uk_snapshot_application` (`application_id`);
  END IF;
END$$
DELIMITER ;

CALL `ccr_add_snapshot_application_unique_key`();
DROP PROCEDURE `ccr_add_snapshot_application_unique_key`;
