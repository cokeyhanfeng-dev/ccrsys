-- ============================================================
-- 客户贡献度与利率决策系统 · 建库脚本
-- 依据:《客户贡献度与利率决策系统开发设计文档-定稿版V1.0》§9、附录A
-- 技术栈: MySQL 8.0 / utf8mb4 / InnoDB
-- 命名:  ccr_* 业务系统自建;dw_*/caps_* 数据仓库落地数据区;flow_* 工作流引擎内部表
-- 金额统一 DECIMAL(20,4)(万元);利率统一 DECIMAL(9,6)(百分数值 3.200000 = 3.20%)
-- 日期字段使用 DATE/DATETIME,禁止字符串存业务日期
-- ============================================================

CREATE DATABASE IF NOT EXISTS `ccr_rate`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;

USE `ccr_rate`;

-- 提示:各脚本执行顺序
--   01_init.sql          建库
--   02_external_data.sql 外部数据落地表(caps_*/dw_*)——数仓契约
--   03_business.sql      业务系统自建表(ccr_*)
--   04_workflow.sql      工作流引擎表(flow_*,由 Warm-Flow 建表,可留空占位)
--   05_seed.sql          字典/参数/角色/菜单等基础数据
