-- ============================================================
-- 运行日志监控:系统运行报错采集表(2026-08-20,增量包 014)
-- 作用:logback 自定义 ERROR_DB Appender 将运行期 ERROR/异常(含完整堆栈)
--      异步落库,前台"系统管理-运行日志监控"页面可搜索/查看详情/标记处理。
--      本表只采集报错(非审计),SQL 打印走日志文件(CCR_SQL logger)。
-- 幂等:CREATE TABLE IF NOT EXISTS,可重复执行。
-- 执行:docker exec -i ccr-mysql mysql -uroot -proot123 --default-character-set=utf8mb4 < db/incr/20260820_002_run_log_monitor.sql
-- 清理:ccr_error_log 按 retention-days 由 RunLogCleanTask 定期 DELETE。
-- ============================================================

USE `ccr_rate`;

CREATE TABLE IF NOT EXISTS `ccr_error_log` (
  `id`            BIGINT       NOT NULL COMMENT '主键(雪花)',
  `error_time`    DATETIME     NOT NULL COMMENT '错误发生时间',
  `logger_name`   VARCHAR(255) NULL COMMENT '产生日志的类全名',
  `level`         VARCHAR(16)  NOT NULL DEFAULT 'ERROR' COMMENT '日志级别(ERROR/WARN,可配置 min-level 采集)',
  `message`       TEXT         NULL COMMENT '错误消息摘要',
  `stack_trace`   MEDIUMTEXT   NULL COMMENT '完整堆栈(含 caused by,前端详情展示)',
  `thread_name`   VARCHAR(128) NULL COMMENT '线程名',
  `request_uri`   VARCHAR(512) NULL COMMENT '请求路径(经 MDC 带入,非请求上下文为空)',
  `operator_id`   BIGINT       NULL COMMENT '操作人ID(经 MDC 带入,非登录上下文为空)',
  `handle_status` VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '处理状态:PENDING待处理/HANDLED已处理/IGNORED忽略',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_error_time` (`error_time`),
  KEY `idx_error_status` (`handle_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ccr_error_log 运行报错采集表(运行日志监控,非审计)';
