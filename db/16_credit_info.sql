-- 授信协议补录/修正快照(§12.7 扩展)
-- 存量=协议带出可修正;新增=手工补录(协议号可空,拟签授信)
-- 随申请提交落库,审批详情「授信信息」区优先展示补录值(source=APPLICATION),数仓兜底
ALTER TABLE ccr_application
    ADD COLUMN credit_info_json json NULL COMMENT '授信协议补录/修正快照(JSON)' AFTER customer_info_json;
