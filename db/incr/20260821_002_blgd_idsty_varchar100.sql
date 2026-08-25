-- 20260821_002 caps_corp_cust_basic_info.blgd_idsty 扩展 VARCHAR(100)
-- 背景:生产数仓推送对公客户主数据时,所属行业值超 VARCHAR(20) 上限(如行业分类码较长),报
--       Data too long for column 'blgd_idsty'。统一扩为 VARCHAR(100) 容纳。
-- 幂等:ALTER TABLE MODIFY 幂等,可重复执行(MySQL 8.0.46 实测无副作用)。
-- 影响表:caps_corp_cust_basic_info(对公客户主数据)。业务代码仅 SELECT(映射别名 industry),
--        无类型依赖,无需改代码。
-- 执行命令:docker exec -i ccr-mysql mysql -uroot -proot123 --default-character-set=utf8mb4 < db/incr/20260821_002_blgd_idsty_varchar100.sql

USE ccr_rate;

ALTER TABLE `caps_corp_cust_basic_info` MODIFY `blgd_idsty` VARCHAR(100) NULL COMMENT '所属行业';

-- 无规则/矩阵/缓存类配置改动,无需清理 Redis key。
