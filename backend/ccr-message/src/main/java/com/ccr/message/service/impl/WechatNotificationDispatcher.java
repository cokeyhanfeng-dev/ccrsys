package com.ccr.message.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ccr.message.domain.CcrNotificationLog;
import com.ccr.message.mapper.CcrNotificationLogMapper;
import com.ccr.message.service.sender.MessageSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** 外部发送先用数据库条件更新认领；超时租约支持进程中断恢复，尝试次数在网络调用前持久化。 */
@Slf4j
@Component
public class WechatNotificationDispatcher {
    private final CcrNotificationLogMapper logs;
    private final List<MessageSender> senders;
    private final int maxAttempts;

    public WechatNotificationDispatcher(CcrNotificationLogMapper logs, List<MessageSender> senders,
                                       @Value("${ccr.message.max-retry:3}") int maxAttempts) {
        this.logs = logs;
        this.senders = senders;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public void dispatch(CcrNotificationLog message) {
        int previous = message.getRetryCount() == null ? 0 : message.getRetryCount();
        if (previous >= maxAttempts) {
            int changed = logs.update(null, eligible(message)
                    .set(CcrNotificationLog::getSendStatus, "FAILED")
                    .set(CcrNotificationLog::getStatus, "FAILED")
                    .set(CcrNotificationLog::getErrorMessage, "企业微信发送中断且已达到尝试上限，请人工核查网关")
                    .set(CcrNotificationLog::getUpdateTime, LocalDateTime.now()));
            if (changed > 0) terminalAlert(message);
            return;
        }
        int attempt = previous + 1;
        int claimed = logs.update(null, eligible(message)
                .set(CcrNotificationLog::getSendStatus, "PROCESSING")
                .set(CcrNotificationLog::getRetryCount, attempt)
                .set(CcrNotificationLog::getUpdateTime, LocalDateTime.now()));
        if (claimed == 0) return;
        boolean success = false;
        String error = null;
        try {
            MessageSender sender = senders.stream().filter(s -> s.supports("WECHAT")).findFirst()
                    .orElseThrow(() -> new IllegalStateException("企业微信渠道未接入"));
            sender.send(message);
            success = true;
        } catch (Exception e) {
            // 不透传第三方响应或异常上下文；完整诊断限于网关自身的受控日志。
            error = "企业微信发送失败，请核查网关配置、接收账号和网关调用结果";
        }
        boolean exhausted = !success && attempt >= maxAttempts;
        LambdaUpdateWrapper<CcrNotificationLog> result = new LambdaUpdateWrapper<CcrNotificationLog>()
                .eq(CcrNotificationLog::getId, message.getId())
                .eq(CcrNotificationLog::getSendStatus, "PROCESSING")
                .eq(CcrNotificationLog::getRetryCount, attempt)
                .set(CcrNotificationLog::getSendStatus, success ? "SUCCESS" : exhausted ? "FAILED" : "RETRYING")
                .set(CcrNotificationLog::getStatus, success ? "SENT" : "FAILED")
                .set(CcrNotificationLog::getErrorMessage, error)
                .set(success, CcrNotificationLog::getSendTime, LocalDateTime.now())
                .set(CcrNotificationLog::getUpdateTime, LocalDateTime.now());
        int saved = logs.update(null, result);
        if (saved > 0 && exhausted) terminalAlert(message);
    }

    private LambdaUpdateWrapper<CcrNotificationLog> eligible(CcrNotificationLog message) {
        return new LambdaUpdateWrapper<CcrNotificationLog>()
                .eq(CcrNotificationLog::getId, message.getId())
                .eq(CcrNotificationLog::getChannel, "WECHAT")
                .eq(CcrNotificationLog::getRetryCount, message.getRetryCount() == null ? 0 : message.getRetryCount())
                .and(w -> w.eq(CcrNotificationLog::getSendStatus, "PENDING")
                        .or(r -> r.eq(CcrNotificationLog::getSendStatus, "RETRYING")
                                .le(CcrNotificationLog::getUpdateTime, LocalDateTime.now().minusMinutes(2)))
                        .or(r -> r.eq(CcrNotificationLog::getSendStatus, "PROCESSING")
                                .le(CcrNotificationLog::getUpdateTime, LocalDateTime.now().minusMinutes(5))));
    }

    private void terminalAlert(CcrNotificationLog message) {
        // 项目已有 ERROR 运行日志采集入库，管理员可在运行日志中处理终态告警。
        log.error("企业微信节点提醒发送终态失败，notificationId={}，messageKey={}，请人工核查",
                message.getId(), message.getMessageKey());
    }
}
