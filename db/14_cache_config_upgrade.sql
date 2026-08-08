-- ============================================================
-- 缓存项定义升级(§3.6 v2):ccr_cache_config 从"覆盖值表"升级为"完整定义表"
-- 前置:13_cache_config.sql 已执行(表存在)
-- 新增列:cache_key(精确 key)/key_pattern(前缀)/description/data_loader/loader_param/is_system(内置标记)
-- 幂等:MySQL 8 无 ADD COLUMN IF NOT EXISTS,用临时存储过程判断列/索引后 ALTER,可重复执行
-- 种子:内置 3 项 ON DUPLICATE KEY UPDATE,UPDATE 子句只写结构列,不动 enabled/ttl_seconds
--       (不覆盖管理员已改的开关/TTL)
-- ============================================================

USE `ccr_rate`;

-- 幂等加列(cache_key)
DROP PROCEDURE IF EXISTS `__ccr_upgrade_cache_config`;
DELIMITER $$
CREATE PROCEDURE `__ccr_upgrade_cache_config`()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA='ccr_rate' AND TABLE_NAME='ccr_cache_config' AND COLUMN_NAME='cache_key') THEN
    ALTER TABLE `ccr_cache_config`
      ADD COLUMN `cache_key` VARCHAR(255) NULL COMMENT '精确缓存 key(与 key_pattern 二选一,如 ccr:cfg:lpr:effective)' AFTER `item_key`;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA='ccr_rate' AND TABLE_NAME='ccr_cache_config' AND COLUMN_NAME='key_pattern') THEN
    ALTER TABLE `ccr_cache_config`
      ADD COLUMN `key_pattern` VARCHAR(255) NULL COMMENT 'key 前缀(前缀匹配,如 ccr:cfg:rate-limit:)' AFTER `cache_key`;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA='ccr_rate' AND TABLE_NAME='ccr_cache_config' AND COLUMN_NAME='description') THEN
    ALTER TABLE `ccr_cache_config`
      ADD COLUMN `description` VARCHAR(500) NULL COMMENT '缓存项展示描述' AFTER `key_pattern`;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA='ccr_rate' AND TABLE_NAME='ccr_cache_config' AND COLUMN_NAME='data_loader') THEN
    ALTER TABLE `ccr_cache_config`
      ADD COLUMN `data_loader` VARCHAR(64) NULL COMMENT '数据加载器编码(如 DW_TABLE;空=业务代码写缓存)' AFTER `description`;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA='ccr_rate' AND TABLE_NAME='ccr_cache_config' AND COLUMN_NAME='loader_param') THEN
    ALTER TABLE `ccr_cache_config`
      ADD COLUMN `loader_param` VARCHAR(1024) NULL COMMENT '加载器参数 JSON(如 {"table":"dw_x","limit":5000})' AFTER `data_loader`;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA='ccr_rate' AND TABLE_NAME='ccr_cache_config' AND COLUMN_NAME='is_system') THEN
    ALTER TABLE `ccr_cache_config`
      ADD COLUMN `is_system` CHAR(1) NOT NULL DEFAULT 'N' COMMENT '内置项标记:Y=内置(不可删/不可改 cache_key) N=自定义' AFTER `loader_param`;
  END IF;

  -- 幂等加唯一索引(前缀项 cache_key 为 NULL,MySQL 唯一索引允许多 NULL 不冲突)
  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA='ccr_rate' AND TABLE_NAME='ccr_cache_config' AND INDEX_NAME='uk_cache_key') THEN
    ALTER TABLE `ccr_cache_config` ADD UNIQUE KEY `uk_cache_key` (`cache_key`);
  END IF;
END $$
DELIMITER ;
CALL `__ccr_upgrade_cache_config`();
DROP PROCEDURE `__ccr_upgrade_cache_config`;

-- ============================================================
-- 内置 3 项种子(幂等;UPDATE 只写结构列,保留管理员已改的 enabled/ttl_seconds)
-- 固定 ID 99001-99003 避开雪花 id 冲突;表无自增,显式填审计列
-- ============================================================
INSERT INTO `ccr_cache_config`
  (`id`,`tenant_id`,`business_no`,`org_id`,`status`,`version_no`,`create_by`,`create_time`,`del_flag`,
   `item_key`,`cache_key`,`key_pattern`,`enabled`,`ttl_seconds`,`description`,`data_loader`,`loader_param`,`is_system`)
VALUES
  (99001,'000000','CACHE_CFG_LPR',0,'ENABLE',1,0,NOW(),'0',
   'lpr-effective','ccr:cfg:lpr:effective',NULL,'Y',NULL,'LPR 当前生效版本',NULL,NULL,'Y')
 ,(99002,'000000','CACHE_CFG_MATRIX',0,'ENABLE',1,0,NOW(),'0',
   'matrix-effective','ccr:cfg:matrix:effective',NULL,'Y',NULL,'利率矩阵生效行',NULL,NULL,'Y')
 ,(99003,'000000','CACHE_CFG_RATE',0,'ENABLE',1,0,NOW(),'0',
   'rate-limit',NULL,'ccr:cfg:rate-limit:','Y',NULL,'产品硬边界限流',NULL,NULL,'Y')
ON DUPLICATE KEY UPDATE
  `cache_key`   = VALUES(`cache_key`),
  `key_pattern` = VALUES(`key_pattern`),
  `description` = VALUES(`description`),
  `data_loader` = VALUES(`data_loader`),
  `loader_param`= VALUES(`loader_param`),
  `is_system`   = VALUES(`is_system`);
