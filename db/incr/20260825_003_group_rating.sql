-- ============================================================
-- 增量 003(2026-08-25):集团五级分类与信用评级
-- 需求:申请页集团信息展示「五级分类」「集团信用评级」(§2026-08-25)。
--   数仓集团主数据 dw_customer_group_snapshot 加 five_level_class/credit_level 两列。
-- 幂等:information_schema 检查列存在,不存在才 ALTER,可重复执行。
-- ============================================================

USE `ccr_rate`;

DROP PROCEDURE IF EXISTS `ccr_add_group_rating`;
DELIMITER $$
CREATE PROCEDURE `ccr_add_group_rating`()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA='ccr_rate' AND TABLE_NAME='dw_customer_group_snapshot'
                   AND COLUMN_NAME='five_level_class') THEN
    ALTER TABLE `dw_customer_group_snapshot`
      ADD COLUMN `five_level_class` VARCHAR(16) NULL COMMENT '五级分类(010正常/020关注/030次级/040可疑/050损失;2026-08-25)',
      ADD COLUMN `credit_level` VARCHAR(32) NULL COMMENT '集团信用评级(如 AAA/AA+;2026-08-25)';
  END IF;
END$$
DELIMITER ;
CALL `ccr_add_group_rating`();
DROP PROCEDURE `ccr_add_group_rating`;

-- 模拟数仓数据:GROUP001 五级分类正常(010)、信用评级 AA+
UPDATE dw_customer_group_snapshot
   SET five_level_class = COALESCE(five_level_class, '010'),
       credit_level = COALESCE(credit_level, 'AA+')
 WHERE group_no = 'GROUP001';
