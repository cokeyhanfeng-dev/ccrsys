-- ══════════════════════════════════════════════════════════════
-- CCR 增量脚本 · 20260818_001 · 停用贷款产品硬边界
-- 基线:publish_full.sql(2026-08-18)
-- 目的:按业务要求,贷款不设最低利率底线;硬边界记录保留(status=INVALID)以备追溯/恢复
-- 影响:ccr_product_rate_limit(LOAN_A/LOAN_P 由 EFFECTIVE→INVALID)
-- 幂等:是(UPDATE 仅命中 status='EFFECTIVE' 的行,重复执行无副作用)
-- 执行:docker exec -i ccr-mysql mysql -uroot -proot123 --default-character-set=utf8mb4 < 20260818_001_disable_loan_hard_boundary.sql
-- ══════════════════════════════════════════════════════════════

USE `ccr_rate`;

-- 1) 对公贷款 LOAN_A:硬边界 2.8% → 停用
UPDATE `ccr_product_rate_limit`
   SET `status` = 'INVALID'
 WHERE `product_code` = 'LOAN_A'
   AND `business_type` = 'LOAN'
   AND `del_flag` = '0'
   AND `status` = 'EFFECTIVE';

-- 2) 个人经营性贷款 LOAN_P:硬边界 3.0% → 停用
UPDATE `ccr_product_rate_limit`
   SET `status` = 'INVALID'
 WHERE `product_code` = 'LOAN_P'
   AND `business_type` = 'LOAN'
   AND `del_flag` = '0'
   AND `status` = 'EFFECTIVE';

-- 3) 部署后清理 Redis 缓存(本机):
--    docker exec ccr-redis redis-cli DEL ccr:cfg:rate-limit:LOAN:LOAN_A ccr:cfg:rate-limit:LOAN:LOAN_P
