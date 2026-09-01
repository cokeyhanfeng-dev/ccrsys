-- 承诺跟踪指标中文名补刷(2026-09-01 修复:建 track 时 metric_name 误存 metric_code,改为存 ccr_metric_definition 中文名)
-- 修复背景:ItemFinalizationServiceImpl.createCommitmentPlan 曾把 metric_code 当 metric_name 存,
-- 导致承诺跟踪页指标显示英文。新代码建跟踪时从 ccr_metric_definition 取中文名;本脚本回刷存量行。
-- 幂等:仅更新 metric_name 仍是英文码(或为空)的行,可重复执行。
UPDATE ccr_commitment_track t
JOIN ccr_metric_definition d
  ON d.metric_code = t.metric_code AND d.del_flag = '0'
SET t.metric_name = d.metric_name
WHERE t.metric_name = t.metric_code OR t.metric_name IS NULL OR t.metric_name = '';
