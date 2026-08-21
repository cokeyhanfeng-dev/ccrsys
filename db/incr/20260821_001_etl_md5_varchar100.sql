-- 20260821_001 etl_md5 字段全量扩容 VARCHAR(100)
-- 背景:生产环境数仓推送 etl_md5 值超长(真实源系统 ETL_MD5 非自增数字),
--       dw_* 表原 BIGINT AUTO_INCREMENT 无法容纳 → 报 Data too long for column 'etl_md5'。
--       caps_* 表原 CHAR(32) 同样容纳不下部分真实值。统一扩为 VARCHAR(100)。
-- 幂等:ALTER TABLE MODIFY 幂等,可重复执行(MySQL 8.0.46 实测:dw_ 表自动去除自增属性、
--       保留主键约束、已有数据保留(数字转字符串)、重复执行无副作用)。
-- 影响表:19 张(2 caps_ + 16 dw_ 活跃 + dw_org_dim 弃用表)。
--         dw_org_dim 为弃用表,一并统一口径(不单独排除)。
-- 注意:dw_* 表 etl_md5 主键改 VARCHAR 后失去自增;数仓推送显式携带 etl_md5 值,不受影响;
--       业务代码只读 dw_* 表(无业务 INSERT),不受影响。
-- 执行命令:docker exec -i ccr-mysql mysql -uroot -proot123 --default-character-set=utf8mb4 < db/incr/20260821_001_etl_md5_varchar100.sql

USE ccr_rate;

-- ---------- caps_* 客户主数据(原 CHAR(32)) ----------
ALTER TABLE `caps_corp_cust_basic_info`  MODIFY `etl_md5` VARCHAR(100) NOT NULL COMMENT '源系统ETL_MD5';
ALTER TABLE `caps_indv_cust_basic_info`  MODIFY `etl_md5` VARCHAR(100) NOT NULL COMMENT '源系统ETL_MD5';

-- ---------- dw_* 快照表(原 BIGINT AUTO_INCREMENT,自动去自增) ----------
ALTER TABLE `dw_mortgage_snapshot`           MODIFY `etl_md5` VARCHAR(100) NOT NULL;
ALTER TABLE `dw_guarantor_snapshot`          MODIFY `etl_md5` VARCHAR(100) NOT NULL;
ALTER TABLE `dw_contribution_metric`         MODIFY `etl_md5` VARCHAR(100) NOT NULL;
ALTER TABLE `dw_credit_report_snapshot`      MODIFY `etl_md5` VARCHAR(100) NOT NULL;
ALTER TABLE `dw_credit_financing_detail`     MODIFY `etl_md5` VARCHAR(100) NOT NULL;
ALTER TABLE `dw_org_performance_snapshot`    MODIFY `etl_md5` VARCHAR(100) NOT NULL;
ALTER TABLE `dw_credit_financing_summary`    MODIFY `etl_md5` VARCHAR(100) NOT NULL;
ALTER TABLE `dw_customer_group_snapshot`     MODIFY `etl_md5` VARCHAR(100) NOT NULL;
ALTER TABLE `dw_customer_group_member_snapshot` MODIFY `etl_md5` VARCHAR(100) NOT NULL;
ALTER TABLE `dw_customer_relation_snapshot`  MODIFY `etl_md5` VARCHAR(100) NOT NULL;
ALTER TABLE `dw_group_credit_snapshot`       MODIFY `etl_md5` VARCHAR(100) NOT NULL;
ALTER TABLE `dw_member_credit_limit_snapshot` MODIFY `etl_md5` VARCHAR(100) NOT NULL;
ALTER TABLE `dw_loan_contract_snapshot`      MODIFY `etl_md5` VARCHAR(100) NOT NULL;
ALTER TABLE `dw_loan_note_snapshot`          MODIFY `etl_md5` VARCHAR(100) NOT NULL;
ALTER TABLE `dw_deposit_account_snapshot`    MODIFY `etl_md5` VARCHAR(100) NOT NULL;
ALTER TABLE `dw_credit_agreement_snapshot`   MODIFY `etl_md5` VARCHAR(100) NOT NULL;

-- ---------- dw_org_dim(弃用表,原 BIGINT 无自增,统一口径) ----------
ALTER TABLE `dw_org_dim`                     MODIFY `etl_md5` VARCHAR(100) NOT NULL;

-- 无规则/矩阵/缓存类配置改动,无需清理 Redis key。
-- 附:若生产 dw_contribution_metric 报错字段为 metric_name(现 VARCHAR(64))而非 etl_md5,
--     需另行评估加宽(见 20260821_001 交付说明,勿在本脚本内擅自加宽)。
