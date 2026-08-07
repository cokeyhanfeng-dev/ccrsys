package com.ccr.common.outbox;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ccr.common.exception.ServiceException;
import com.ccr.common.outbox.domain.CcrOutboxEvent;
import com.ccr.common.outbox.mapper.CcrOutboxEventMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Outbox 发布测试:event_no 确定性生成 + 唯一约束幂等(§3.5)
 */
@ExtendWith(MockitoExtension.class)
class OutboxServiceImplTest {

    @Mock
    private CcrOutboxEventMapper outboxEventMapper;

    @InjectMocks
    private OutboxServiceImpl outboxService;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CcrOutboxEvent.class);
    }

    @Test
    void publish_insertsPendingEventWithDeterministicEventNo() {
        when(outboxEventMapper.insert(any(CcrOutboxEvent.class))).thenReturn(1);

        CcrOutboxEvent event = outboxService.publish("FLOW_START", "PI-1", "{\"pricingItemNo\":\"PI-1\"}");

        assertEquals("FLOW_START:PI-1", event.getEventNo());
        assertEquals("FLOW_START", event.getEventType());
        assertEquals(OutboxServiceImpl.STATUS_PENDING, event.getStatus());
        assertEquals(0, event.getRetryCount());
        assertEquals(5, event.getMaxRetry());
        assertNotNull(event.getNextRetryTime());
        verify(outboxEventMapper).insert(argThat((CcrOutboxEvent e) ->
                "FLOW_START:PI-1".equals(e.getEventNo()) && e.getPayload().contains("PI-1")));
    }

    @Test
    void publish_duplicateEventNo_isIdempotent() {
        when(outboxEventMapper.insert(any(CcrOutboxEvent.class)))
                .thenThrow(new DuplicateKeyException("uk_event_no"));
        CcrOutboxEvent existing = new CcrOutboxEvent();
        existing.setEventNo("NOTIFY:SUBMIT:APP:1:APPLICANT");
        when(outboxEventMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        CcrOutboxEvent result = outboxService.publish("NOTIFY", "SUBMIT:APP:1:APPLICANT", "{\"content\":\"x\"}");

        // 重复发布不抛异常,返回已存在事件
        assertSame(existing, result);
    }

    @Test
    void publish_blankArgs_rejected() {
        assertThrows(ServiceException.class, () -> outboxService.publish(null, "K", "{}"));
        assertThrows(ServiceException.class, () -> outboxService.publish("FLOW_START", " ", "{}"));
        assertThrows(ServiceException.class, () -> outboxService.publish("FLOW_START", "K", null));
    }
}
