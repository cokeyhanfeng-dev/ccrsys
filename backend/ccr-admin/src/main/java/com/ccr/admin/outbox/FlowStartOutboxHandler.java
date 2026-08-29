package com.ccr.admin.outbox;

import cn.hutool.json.JSONUtil;
import com.ccr.common.outbox.OutboxEventHandler;
import com.ccr.common.outbox.OutboxEventType;
import com.ccr.common.outbox.domain.CcrOutboxEvent;
import com.ccr.workflow.service.WarmFlowService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * FLOW_START 事件处理器:按路由起始节点发起 Warm-Flow 流程实例(§7.2 提交后异步)
 * 幂等:flow_instance 按 business_id 查重,已发起则跳过
 * 整单交付改造(2026-08-29):business_id 从定价分项编号改申请单编号(整单一条流程实例)
 */
@Slf4j
@Component
public class FlowStartOutboxHandler implements OutboxEventHandler {

    @Resource
    private WarmFlowService warmFlowService;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public String eventType() {
        return OutboxEventType.FLOW_START;
    }

    @Override
    public void handle(CcrOutboxEvent event) {
        var payload = JSONUtil.parseObj(event.getPayload());
        String businessId = payload.getStr("applicationNo");
        String nodeCode = payload.getStr("nodeCode");
        String flowCode = payload.getStr("flowCode", WarmFlowService.STANDARD_FLOW_CODE);
        String createBy = payload.getStr("createBy", "0");
        // 幂等:同申请流程实例已存在则跳过
        Long exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM flow_instance WHERE business_id = ?", Long.class, businessId);
        if (exists != null && exists > 0) {
            log.info("申请 {} 流程实例已存在,FLOW_START 幂等跳过", businessId);
            return;
        }
        Long instanceId = warmFlowService.start(flowCode, businessId, createBy, nodeCode);
        log.info("FLOW_START 消费完成: 申请 {} 流程实例 {}", businessId, instanceId);
    }
}
