package com.ccr.admin.outbox;

import cn.hutool.json.JSONUtil;
import com.ccr.common.outbox.OutboxEventHandler;
import com.ccr.common.outbox.OutboxEventType;
import com.ccr.common.outbox.domain.CcrOutboxEvent;
import com.ccr.vote.service.ItemFinalizationService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * COMMITMENT_CREATE 事件处理器:委托 ccr-vote 终态串联服务按申请承诺指标建承诺计划
 * (按 resolution_id 幂等)
 */
@Component
public class CommitmentCreateOutboxHandler implements OutboxEventHandler {

    @Resource
    private ItemFinalizationService itemFinalizationService;

    @Override
    public String eventType() {
        return OutboxEventType.COMMITMENT_CREATE;
    }

    @Override
    public void handle(CcrOutboxEvent event) {
        itemFinalizationService.processCommitmentCreate(JSONUtil.parseObj(event.getPayload()));
    }
}
