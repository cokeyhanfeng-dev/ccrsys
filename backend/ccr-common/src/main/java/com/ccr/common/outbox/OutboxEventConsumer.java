package com.ccr.common.outbox;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ccr.common.outbox.domain.CcrOutboxEvent;
import com.ccr.common.outbox.domain.OutboxAlertNotification;
import com.ccr.common.outbox.mapper.CcrOutboxEventMapper;
import com.ccr.common.outbox.mapper.OutboxAlertNotificationMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Outbox 事件消费者(§3.5)
 * 定时扫描 PENDING 且 next_retry_time 到期的事件,按 eventType 分发处理器;
 * 处理成功置 SUCCESS;失败按指数退避(1m/5m/15m/1h/4h/12h 封顶)重试,
 * 超过 max_retry 置 FAILED 并写告警通知(ccr_notification_log,message_key 幂等)。
 * 幂等:PENDING→PROCESSING 乐观认领防并发重复消费;处理器按 event_no/业务键自身幂等。
 */
@Slf4j
@Component
public class OutboxEventConsumer {

    /** 指数退避分钟数(重试第 1..N 次,超出取末位封顶) */
    private static final long[] BACKOFF_MINUTES = {1, 5, 15, 60, 240, 720};

    /** 事件类型 → 处理器(实现注册在 ccr-admin 等能访问业务 Service 的模块,避免循环依赖) */
    private final Map<String, OutboxEventHandler> handlers = new HashMap<>();

    @Value("${ccr.outbox.batch-size:50}")
    private int batchSize = 50;

    @Resource
    private CcrOutboxEventMapper outboxEventMapper;
    @Resource
    private OutboxAlertNotificationMapper alertNotificationMapper;

    public OutboxEventConsumer(List<OutboxEventHandler> handlerList) {
        if (handlerList != null) {
            for (OutboxEventHandler handler : handlerList) {
                handlers.put(handler.eventType(), handler);
            }
        }
    }

    /** 定时扫描入口(cron 配置 ccr.outbox.scan-cron,默认每 30 秒) */
    @Scheduled(cron = "${ccr.outbox.scan-cron:0/30 * * * * *}")
    public void scan() {
        processBatch();
    }

    /** 扫描并处理一批到期事件,返回处理条数 */
    public int processBatch() {
        List<CcrOutboxEvent> events = outboxEventMapper.selectList(new LambdaQueryWrapper<CcrOutboxEvent>()
                .eq(CcrOutboxEvent::getStatus, OutboxServiceImpl.STATUS_PENDING)
                .and(w -> w.isNull(CcrOutboxEvent::getNextRetryTime)
                        .or().le(CcrOutboxEvent::getNextRetryTime, LocalDateTime.now()))
                .orderByAsc(CcrOutboxEvent::getId)
                .last("LIMIT " + batchSize));
        int processed = 0;
        for (CcrOutboxEvent event : events) {
            processOne(event);
            processed++;
        }
        return processed;
    }

    /** 单事件处理:乐观认领 → 分发 → 成功/退避重试/终态告警 */
    public void processOne(CcrOutboxEvent event) {
        // PENDING→PROCESSING 乐观认领(多实例/重复扫描防重)
        int claimed = outboxEventMapper.update(null, new LambdaUpdateWrapper<CcrOutboxEvent>()
                .eq(CcrOutboxEvent::getId, event.getId())
                .eq(CcrOutboxEvent::getStatus, OutboxServiceImpl.STATUS_PENDING)
                .set(CcrOutboxEvent::getStatus, OutboxServiceImpl.STATUS_PROCESSING)
                .set(CcrOutboxEvent::getUpdateTime, LocalDateTime.now()));
        if (claimed == 0) {
            return;
        }
        OutboxEventHandler handler = handlers.get(event.getEventType());
        if (handler == null) {
            // 无注册处理器,重试无意义,直接终态告警
            markFailed(event, "未注册事件处理器: " + event.getEventType());
            return;
        }
        try {
            handler.handle(event);
            outboxEventMapper.update(null, new LambdaUpdateWrapper<CcrOutboxEvent>()
                    .eq(CcrOutboxEvent::getId, event.getId())
                    .set(CcrOutboxEvent::getStatus, OutboxServiceImpl.STATUS_SUCCESS)
                    .set(CcrOutboxEvent::getLastError, null)
                    .set(CcrOutboxEvent::getUpdateTime, LocalDateTime.now()));
            log.info("Outbox 事件消费成功: {} {}", event.getEventNo(), event.getEventType());
        } catch (Exception e) {
            int retryCount = (event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1;
            int maxRetry = event.getMaxRetry() == null ? 5 : event.getMaxRetry();
            String error = StrUtil.maxLength(
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 1800);
            if (retryCount >= maxRetry) {
                markFailed(event, retryCount, error);
            } else {
                LocalDateTime nextRetry = LocalDateTime.now()
                        .plusMinutes(BACKOFF_MINUTES[Math.min(retryCount - 1, BACKOFF_MINUTES.length - 1)]);
                outboxEventMapper.update(null, new LambdaUpdateWrapper<CcrOutboxEvent>()
                        .eq(CcrOutboxEvent::getId, event.getId())
                        .set(CcrOutboxEvent::getStatus, OutboxServiceImpl.STATUS_PENDING)
                        .set(CcrOutboxEvent::getRetryCount, retryCount)
                        .set(CcrOutboxEvent::getNextRetryTime, nextRetry)
                        .set(CcrOutboxEvent::getLastError, error)
                        .set(CcrOutboxEvent::getUpdateTime, LocalDateTime.now()));
                log.warn("Outbox 事件消费失败,第 {} 次重试定于 {}: {} - {}",
                        retryCount, nextRetry, event.getEventNo(), e.getMessage());
            }
        }
    }

    /** 置 FAILED 终态并写告警通知(message_key=OUTBOX_FAIL:{eventNo} 幂等) */
    private void markFailed(CcrOutboxEvent event, String error) {
        markFailed(event, event.getRetryCount() == null ? 0 : event.getRetryCount(), error);
    }

    /** 置 FAILED 终态并写告警通知(message_key=OUTBOX_FAIL:{eventNo} 幂等) */
    private void markFailed(CcrOutboxEvent event, int retryCount, String error) {
        String safeError = error == null ? "未知错误" : error;
        outboxEventMapper.update(null, new LambdaUpdateWrapper<CcrOutboxEvent>()
                .eq(CcrOutboxEvent::getId, event.getId())
                .set(CcrOutboxEvent::getStatus, OutboxServiceImpl.STATUS_FAILED)
                .set(CcrOutboxEvent::getRetryCount, retryCount)
                .set(CcrOutboxEvent::getLastError, StrUtil.maxLength(safeError, 1800))
                .set(CcrOutboxEvent::getUpdateTime, LocalDateTime.now()));
        log.error("Outbox 事件消费终态失败: {} {} - {}", event.getEventNo(), event.getEventType(), safeError);
        try {
            OutboxAlertNotification alert = new OutboxAlertNotification();
            alert.setStatus("SENDING");
            alert.setRuleVersionId(0L);
            alert.setRecipientType("ROLE");
            alert.setRecipientId("admin");
            alert.setChannel("SYSTEM");
            alert.setMessageKey("OUTBOX_FAIL:" + event.getEventNo());
            alert.setMessageContent("Outbox 事件消费失败(" + event.getEventType() + "/" + event.getEventNo()
                    + "):" + StrUtil.maxLength(safeError, 1500));
            alert.setSendStatus("PENDING");
            alert.setRetryCount(0);
            alertNotificationMapper.insert(alert);
        } catch (DuplicateKeyException e) {
            // 同事件重复告警:幂等跳过
            log.info("Outbox 告警通知已存在,幂等跳过: {}", event.getEventNo());
        } catch (Exception e) {
            log.error("Outbox 告警通知落库异常: {}", event.getEventNo(), e);
        }
    }
}
