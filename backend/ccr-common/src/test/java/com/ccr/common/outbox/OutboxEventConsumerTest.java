package com.ccr.common.outbox;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ccr.common.outbox.domain.CcrOutboxEvent;
import com.ccr.common.outbox.domain.OutboxAlertNotification;
import com.ccr.common.outbox.mapper.CcrOutboxEventMapper;
import com.ccr.common.outbox.mapper.OutboxAlertNotificationMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Outbox 消费者测试:认领防重/成功置 SUCCESS/指数退避重试/超上限 FAILED+告警/无处理器终态(§3.5)
 */
@ExtendWith(MockitoExtension.class)
class OutboxEventConsumerTest {

    @Mock
    private CcrOutboxEventMapper outboxEventMapper;
    @Mock
    private OutboxAlertNotificationMapper alertNotificationMapper;

    private OutboxEventConsumer consumer;
    private RecordingHandler handler;

    /** 记录调用的事件处理器 */
    static class RecordingHandler implements OutboxEventHandler {
        CcrOutboxEvent received;
        RuntimeException failure;

        @Override
        public String eventType() {
            return "FLOW_START";
        }

        @Override
        public void handle(CcrOutboxEvent event) {
            this.received = event;
            if (failure != null) {
                throw failure;
            }
        }
    }

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CcrOutboxEvent.class);
        TableInfoHelper.initTableInfo(assistant, OutboxAlertNotification.class);
    }

    @BeforeEach
    void setUp() {
        handler = new RecordingHandler();
        consumer = new OutboxEventConsumer(List.of(handler));
        ReflectionTestUtils.setField(consumer, "outboxEventMapper", outboxEventMapper);
        ReflectionTestUtils.setField(consumer, "alertNotificationMapper", alertNotificationMapper);
    }

    private CcrOutboxEvent event(int retryCount, int maxRetry) {
        CcrOutboxEvent event = new CcrOutboxEvent();
        event.setId(100L);
        event.setEventNo("FLOW_START:PI-1");
        event.setEventType("FLOW_START");
        event.setPayload("{}");
        event.setStatus(OutboxServiceImpl.STATUS_PENDING);
        event.setRetryCount(retryCount);
        event.setMaxRetry(maxRetry);
        return event;
    }

    /** 最近一次状态流转 update 的参数值 */
    private Object lastStatusUpdateValue(String key) {
        ArgumentCaptor<LambdaUpdateWrapper> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(outboxEventMapper, org.mockito.Mockito.atLeastOnce()).update(isNull(), captor.capture());
        return captor.getAllValues().get(captor.getAllValues().size() - 1)
                .getParamNameValuePairs().values().stream()
                .filter(v -> key.equals(String.valueOf(v))).findFirst().orElse(null);
    }

    @Test
    void processOne_success_marksSuccess() {
        when(outboxEventMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        consumer.processOne(event(0, 5));

        assertEquals("FLOW_START:PI-1", handler.received.getEventNo());
        // 认领(PROCESSING) + 成功(SUCCESS) 两次更新
        verify(outboxEventMapper, times(2)).update(isNull(), any(Wrapper.class));
        assertEquals(OutboxServiceImpl.STATUS_SUCCESS, lastStatusUpdateValue(OutboxServiceImpl.STATUS_SUCCESS));
        verify(alertNotificationMapper, never()).insert(any(OutboxAlertNotification.class));
    }

    @Test
    void processOne_claimedByOther_skips() {
        // 乐观认领失败(已被并发消费者认领)
        when(outboxEventMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        consumer.processOne(event(0, 5));

        assertEquals(null, handler.received);
        verify(outboxEventMapper, times(1)).update(isNull(), any(Wrapper.class));
    }

    @Test
    void processOne_failure_retriesWithBackoff() {
        when(outboxEventMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        handler.failure = new RuntimeException("网络抖动");

        LocalDateTime before = LocalDateTime.now();
        consumer.processOne(event(0, 5));

        // 回到 PENDING,retry_count=1,next_retry_time ≈ now+1min(退避第一档)
        ArgumentCaptor<LambdaUpdateWrapper> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(outboxEventMapper, times(2)).update(isNull(), captor.capture());
        LambdaUpdateWrapper<?> retryUpdate = captor.getAllValues().get(1);
        assertTrue(retryUpdate.getParamNameValuePairs().containsValue(OutboxServiceImpl.STATUS_PENDING));
        assertTrue(retryUpdate.getParamNameValuePairs().containsValue(1));
        LocalDateTime nextRetry = retryUpdate.getParamNameValuePairs().values().stream()
                .filter(LocalDateTime.class::isInstance).map(LocalDateTime.class::cast)
                .max(LocalDateTime::compareTo).orElseThrow();
        assertTrue(nextRetry.isAfter(before.plusSeconds(50)) && nextRetry.isBefore(before.plusMinutes(2)));
        // 未到上限,不告警
        verify(alertNotificationMapper, never()).insert(any(OutboxAlertNotification.class));
    }

    @Test
    void processOne_failureBeyondMaxRetry_marksFailedAndAlerts() {
        when(outboxEventMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        handler.failure = new RuntimeException("决议服务不可用");

        consumer.processOne(event(4, 5));

        // 第 5 次失败达到 max_retry → FAILED + 告警通知(message_key=OUTBOX_FAIL:{eventNo})
        assertEquals(OutboxServiceImpl.STATUS_FAILED, lastStatusUpdateValue(OutboxServiceImpl.STATUS_FAILED));
        verify(alertNotificationMapper).insert(argThat((OutboxAlertNotification n) ->
                "OUTBOX_FAIL:FLOW_START:PI-1".equals(n.getMessageKey())
                        && "PENDING".equals(n.getSendStatus())
                        && n.getMessageContent().contains("决议服务不可用")));
    }

    @Test
    void processOne_noHandler_marksFailedAndAlerts() {
        consumer = new OutboxEventConsumer(List.of());
        ReflectionTestUtils.setField(consumer, "outboxEventMapper", outboxEventMapper);
        ReflectionTestUtils.setField(consumer, "alertNotificationMapper", alertNotificationMapper);
        when(outboxEventMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        consumer.processOne(event(0, 5));

        assertEquals(OutboxServiceImpl.STATUS_FAILED, lastStatusUpdateValue(OutboxServiceImpl.STATUS_FAILED));
        verify(alertNotificationMapper).insert(argThat((OutboxAlertNotification n) ->
                n.getMessageContent().contains("未注册事件处理器")));
    }

    @Test
    void processOne_alertDuplicateKey_isIdempotent() {
        when(outboxEventMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        doThrow(new DuplicateKeyException("uk_message_key")).when(alertNotificationMapper).insert(any(OutboxAlertNotification.class));
        handler.failure = new RuntimeException("x");

        // 告警 message_key 重复:幂等跳过不抛异常
        assertDoesNotThrow(() -> consumer.processOne(event(4, 5)));
    }

    @Test
    void processBatch_scansPendingDueEvents() {
        CcrOutboxEvent e1 = event(0, 5);
        CcrOutboxEvent e2 = event(1, 5);
        e2.setId(101L);
        e2.setEventNo("FLOW_START:PI-2");
        when(outboxEventMapper.selectList(any(Wrapper.class))).thenReturn(List.of(e1, e2));
        when(outboxEventMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        int processed = consumer.processBatch();

        assertEquals(2, processed);
        assertEquals("FLOW_START:PI-2", handler.received.getEventNo());
    }
}
