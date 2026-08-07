package com.ccr.message.job;

import com.ccr.message.service.NotificationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 通知发送与重试定时任务(§11.4)
 * 消费 ccr_notification_log 中 send_status=PENDING 的记录(resolution 等模块以表为契约写入),
 * 并对 FAILED/RETRYING 且未超上限的记录重发。cron 走配置 ccr.message.retry-cron。
 */
@Slf4j
@Component
public class NotificationRetryJob {

    @Resource
    private NotificationService notificationService;

    @Scheduled(cron = "${ccr.message.retry-cron:0 */2 * * * ?}")
    public void processPendingAndRetry() {
        int processed = notificationService.processPendingAndRetry();
        if (processed > 0) {
            log.info("通知重试任务处理 {} 条", processed);
        }
    }
}
