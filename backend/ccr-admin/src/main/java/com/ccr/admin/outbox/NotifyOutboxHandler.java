package com.ccr.admin.outbox;

import cn.hutool.json.JSONUtil;
import com.ccr.common.outbox.OutboxEventHandler;
import com.ccr.common.outbox.OutboxEventType;
import com.ccr.common.outbox.domain.CcrOutboxEvent;
import com.ccr.message.service.NotificationService;
import com.ccr.message.service.dto.NotificationMessage;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * NOTIFY 事件处理器:调 NotificationService 发送(messageKey 幂等防重)
 * payload: recipientType/recipientId/channel/messageKey/content
 */
@Component
public class NotifyOutboxHandler implements OutboxEventHandler {

    @Resource
    private NotificationService notificationService;

    @Override
    public String eventType() {
        return OutboxEventType.NOTIFY;
    }

    @Override
    public void handle(CcrOutboxEvent event) {
        var payload = JSONUtil.parseObj(event.getPayload());
        NotificationMessage message = new NotificationMessage();
        message.setRecipientType(payload.getStr("recipientType"));
        message.setRecipientId(payload.getStr("recipientId"));
        message.setChannel(payload.getStr("channel"));
        message.setMessageKey(payload.getStr("messageKey"));
        message.setContent(payload.getStr("content"));
        notificationService.sendNotification(message);
    }
}
