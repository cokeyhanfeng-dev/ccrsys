-- 20260821_003 ccr_application_related_person 增加 cert_type 列
-- 背景:关联人贡献度归并改为"仅前台录入关联人"(增量021)。前端关联人编辑器已记录证件类型
--      (certType,USCC对公/ID_CARD对私),但落库未存;后端兜底反查(证件号→数仓主数据→客户号)
--      需按证件类型区分对公/对私主数据表。
-- 幂等:ALTER ADD COLUMN(MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS),重复执行报
--      Duplicate column name 'cert_type' 可忽略(与既有增量脚本幂等约定一致)。
-- 执行命令:docker exec -i ccr-mysql mysql -uroot -proot123 --default-character-set=utf8mb4 < db/incr/20260821_003_related_person_cert_type.sql

USE ccr_rate;

ALTER TABLE `ccr_application_related_person`
  ADD COLUMN `cert_type` VARCHAR(16) NULL
  COMMENT '证件类型(USCC对公/ID_CARD对私;后端兜底反查关联人客户号用)'
  AFTER `cert_no`;

-- 无规则/矩阵/缓存类配置改动,无需清理 Redis key。
