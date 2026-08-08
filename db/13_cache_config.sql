-- ============================================================
-- 缓存项运行时配置(§3.6):覆盖 application.yml 静态默认,修改后立即生效(不重启)
-- 优先级:DB 覆盖值 > yml ccr.cache.items > 显式 TTL > 全局 default-ttl-seconds
-- item_key 唯一;enabled=写入开关(Y 写缓存 / N 直查库);ttl_seconds NULL=回退 yml/全局默认
-- 管理端接口:GET/PUT /system/cache-configs(仅 admin 角色)
-- ============================================================

USE `ccr_rate`;

CREATE TABLE IF NOT EXISTS `ccr_cache_config` (
  `id`          BIGINT       NOT NULL,
  `tenant_id`   VARCHAR(20)  NOT NULL DEFAULT '000000',
  `business_no` VARCHAR(64)  NOT NULL,
  `org_id`      BIGINT       NOT NULL,
  `status`      VARCHAR(32)  NOT NULL DEFAULT 'ENABLE' COMMENT 'ENABLE/DISABLE',
  `version_no`  INT          NOT NULL DEFAULT 1,
  `create_dept` BIGINT       NULL,
  `create_by`   BIGINT       NOT NULL,
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_by`   BIGINT       NULL,
  `update_time` DATETIME     NULL,
  `del_flag`    CHAR(1)      NOT NULL DEFAULT '0',
  `item_key`    VARCHAR(64)  NOT NULL COMMENT '缓存项编码:lpr-effective/matrix-effective/rate-limit',
  `enabled`     CHAR(1)      NOT NULL DEFAULT 'Y' COMMENT '写入开关:Y 启用(写缓存) N 禁用(直查库)',
  `ttl_seconds` BIGINT       NULL COMMENT 'TTL 秒;NULL=用 yml/全局默认',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cache_item` (`item_key`)
) ENGINE=InnoDB COMMENT='ccr_cache_config 缓存项运行时配置(覆盖yml默认值)';

-- 种子:默认不插初始记录(管理端 GET 由 枚举+yml+DB 合并展示,source 为 YML 即无覆盖)。
-- 如需环境预置,可放开以下注释:
-- INSERT INTO `ccr_cache_config` (`id`,`business_no`,`org_id`,`status`,`version_no`,`create_by`,`item_key`,`enabled`,`ttl_seconds`)
-- VALUES (9001,'CACHE_CFG_LPR',0,'ENABLE',1,0,'lpr-effective','Y',NULL)
--      , (9002,'CACHE_CFG_MATRIX',0,'ENABLE',1,0,'matrix-effective','Y',NULL)
--      , (9003,'CACHE_CFG_RATE',0,'ENABLE',1,0,'rate-limit','Y',NULL)
-- ON DUPLICATE KEY UPDATE `enabled`=VALUES(`enabled`);
