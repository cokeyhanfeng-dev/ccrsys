package com.ccr.admin.outbox;

import cn.hutool.json.JSONUtil;
import com.ccr.common.outbox.OutboxEventHandler;
import com.ccr.common.outbox.OutboxEventType;
import com.ccr.common.outbox.domain.CcrOutboxEvent;
import com.ccr.vote.service.ItemFinalizationService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * RESOLUTION_CREATE 事件处理器:委托 ccr-vote 终态串联服务生成决议(已幂等),
 * 成功后链式写 COMMITMENT_CREATE/NOTIFY 事件
 */
@Component
public class ResolutionCreateOutboxHandler implements OutboxEventHandler {

    @Resource
    private ItemFinalizationService itemFinalizationService;

    @Override
    public String eventType() {
        return OutboxEventType.RESOLUTION_CREATE;
    }

    @Override
    public void handle(CcrOutboxEvent event) {
        itemFinalizationService.processResolutionCreate(JSONUtil.parseObj(event.getPayload()));
    }
}
