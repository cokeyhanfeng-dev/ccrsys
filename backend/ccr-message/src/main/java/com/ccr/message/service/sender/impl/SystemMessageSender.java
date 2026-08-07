package com.ccr.message.service.sender.impl;

import com.ccr.message.domain.CcrNotificationLog;
import com.ccr.message.service.sender.MessageSender;
import org.springframework.stereotype.Component;

/**
 * SYSTEM 站内信渠道:首期落库即达,消息体已在 ccr_notification_log,无需外部调用
 */
@Component
public class SystemMessageSender implements MessageSender {

    @Override
    public boolean supports(String channel) {
        return channel == null || channel.isBlank()
                || "SYSTEM".equalsIgnoreCase(channel)
                || "站内信".equals(channel);
    }

    @Override
    public void send(CcrNotificationLog log) {
        // 落库即达:无外部调用,直接视为成功
    }
}
