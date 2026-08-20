-- ============================================================
-- 增量 003：ccr_application 新增 group_info_json(集团补录/申请额度快照)
-- 背景:集团补录集成至利率申请页(docs/19 集团补录集成申请页_需求.md)。
--   集团申请时按「数仓为准」判定新增/存量:新增集团就地补录对公客户全套信息,
--   存量集团授信缺失就地补录「申请额度」(本次新增授信,必填,随申请存多条并存),
--   成员缺失就地补录成员(对公全套+成员要素)。
--   该 JSON 随申请上下文保存(参照 customer_info_json/credit_info_json),提交时解析
--   落 ccr_group/ccr_group_member(幂等、数仓优先、最新覆盖);审批详情优先展示。
-- 幂等:ALTER ADD COLUMN 可重复执行(MySQL 不支持 ADD COLUMN IF NOT EXISTS,重复执行报
--   Duplicate column 时忽略即可)。
-- ============================================================

USE `ccr_rate`;

ALTER TABLE ccr_application
    ADD COLUMN group_info_json json NULL COMMENT '集团补录快照(集团对公全套/本次申请额度/手工补录成员;JSON)' AFTER credit_info_json;
