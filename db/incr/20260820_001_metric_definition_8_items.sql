-- ============================================================
-- 增量 001：承诺指标字典收敛为恰好 8 个对公指标
-- 背景:贡献度口径收敛(用户确认)——指标集合恰好 8 项:
--   存款年日均/贷款年日均/贴现年日均/存贷款比/当年代发金额/当年代发户数/
--   理财年日均余额/结售汇余额。ccr_metric_definition 保留 8 项并改名对齐口径,
--   其余 8 项(TOTAL/GM_*/对私3/对公中间业务/OTHER)停用(status=INACTIVE),
--   前端只读 /ccr/metric-definitions/enabled(仅 ACTIVE)即仅见 8 项;
--   历史承诺/快照引用的 code 记录保留,不悬空。
-- 影响表:ccr_metric_definition(仅 status/metric_name/value_type)
-- 幂等:UPDATE 按 metric_code 精确更新,重复执行结果一致
-- 执行:docker exec -i ccr-mysql mysql -uroot -proot123 --default-character-set=utf8mb4 < db/incr/20260820_001_metric_definition_8_items.sql
-- ============================================================

USE `ccr_rate`;

-- 1) 保留 8 项:中文名对齐业务口径 + 口径型调整(贴现/理财改为日均余额 AVG_BALANCE)
UPDATE ccr_metric_definition SET metric_name='存款年日均'        WHERE metric_code='PUBLIC_DEPOSIT_AVG';
UPDATE ccr_metric_definition SET metric_name='贷款年日均'        WHERE metric_code='PUBLIC_PROJECT_LOAN_AVG';
UPDATE ccr_metric_definition SET metric_name='贴现年日均', value_type='AVG_BALANCE' WHERE metric_code='PUBLIC_DISCOUNT_SPREAD';
UPDATE ccr_metric_definition SET metric_name='存贷款比'          WHERE metric_code='PUBLIC_DEPOSIT_LOAN_RATIO';
UPDATE ccr_metric_definition SET metric_name='当年代发金额'      WHERE metric_code='PUBLIC_PAYROLL_AMOUNT';
UPDATE ccr_metric_definition SET metric_name='当年代发户数'      WHERE metric_code='PUBLIC_PAYROLL_CONTRIBUTION';
UPDATE ccr_metric_definition SET metric_name='理财年日均余额', value_type='AVG_BALANCE' WHERE metric_code='PUBLIC_WEALTH_INCOME';
UPDATE ccr_metric_definition SET metric_name='结售汇余额'        WHERE metric_code='PUBLIC_EXCHANGE_SPREAD';

-- 2) 停用其余 8 项(仅保留 8 个对公指标为启用)
UPDATE ccr_metric_definition SET status='INACTIVE'
 WHERE metric_code IN ('TOTAL','GM_LOAN_CONTRIBUTION','GM_DEPOSIT_CONTRIBUTION','PUBLIC_OFF_BALANCE_INCOME',
                       'PRIVATE_DEPOSIT_AVG','PRIVATE_LOAN_AVG','PRIVATE_WEALTH_INCOME','OTHER');
