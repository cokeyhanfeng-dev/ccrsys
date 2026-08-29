package com.ccr.admin.outbox;

import com.ccr.common.outbox.domain.CcrOutboxEvent;
import com.ccr.message.service.NotificationService;
import com.ccr.message.service.dto.NotificationMessage;
import com.ccr.vote.service.ItemFinalizationService;
import com.ccr.workflow.service.WarmFlowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Outbox 处理器测试(ccr-admin 注册):FLOW_START 发起流程(幂等)、NOTIFY 发送、决议/承诺委托
 */
@ExtendWith(MockitoExtension.class)
class OutboxHandlersTest {

    @Mock
    private WarmFlowService warmFlowService;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private NotificationService notificationService;
    @Mock
    private ItemFinalizationService itemFinalizationService;

    private CcrOutboxEvent event(String type, String payload) {
        CcrOutboxEvent event = new CcrOutboxEvent();
        event.setId(1L);
        event.setEventNo(type + ":K1");
        event.setEventType(type);
        event.setPayload(payload);
        return event;
    }

    @Test
    void flowStart_startsInstanceWhenAbsent() {
        // 整单交付改造(2026-08-29):FLOW_START 逐分项一条改整单一条,business_id=applicationNo
        FlowStartOutboxHandler handler = new FlowStartOutboxHandler();
        ReflectionTestUtils.setField(handler, "warmFlowService", warmFlowService);
        ReflectionTestUtils.setField(handler, "jdbcTemplate", jdbcTemplate);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(warmFlowService.start(anyString(), anyString(), anyString(), anyString())).thenReturn(9001L);

        handler.handle(event("FLOW_START",
                "{\"applicationId\":1,\"applicationNo\":\"CCR20260806ABCD\",\"nodeCode\":\"BRANCH_MANAGER\","
                        + "\"routeCode\":\"SIX_PEOPLE_GROUP\",\"flowCode\":\"rate_approval\",\"createBy\":\"1001\"}"));

        verify(warmFlowService).start("rate_approval", "CCR20260806ABCD", "1001", "BRANCH_MANAGER");
    }

    @Test
    void flowStart_existingInstance_idempotentSkip() {
        FlowStartOutboxHandler handler = new FlowStartOutboxHandler();
        ReflectionTestUtils.setField(handler, "warmFlowService", warmFlowService);
        ReflectionTestUtils.setField(handler, "jdbcTemplate", jdbcTemplate);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);

        handler.handle(event("FLOW_START",
                "{\"applicationId\":1,\"applicationNo\":\"CCR20260806ABCD\",\"nodeCode\":\"BRANCH_MANAGER\",\"flowCode\":\"rate_approval\"}"));

        verify(warmFlowService, never()).start(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void notify_mapsPayloadToNotificationMessage() {
        NotifyOutboxHandler handler = new NotifyOutboxHandler();
        ReflectionTestUtils.setField(handler, "notificationService", notificationService);

        handler.handle(event("NOTIFY",
                "{\"recipientType\":\"USER\",\"recipientId\":\"1001\",\"channel\":\"SYSTEM\","
                        + "\"orgId\":1007,\"messageKey\":\"RES_ISSUED:500\",\"content\":\"决议已签发\"}"));

        verify(notificationService).sendNotification(argThat((NotificationMessage m) ->
                "USER".equals(m.getRecipientType()) && "1001".equals(m.getRecipientId())
                        && Long.valueOf(1007L).equals(m.getOrgId())
                        && "RES_ISSUED:500".equals(m.getMessageKey()) && "决议已签发".equals(m.getContent())));
    }

    @Test
    void resolutionCreate_delegatesToFinalizationService() {
        ResolutionCreateOutboxHandler handler = new ResolutionCreateOutboxHandler();
        ReflectionTestUtils.setField(handler, "itemFinalizationService", itemFinalizationService);

        handler.handle(event("RESOLUTION_CREATE", "{\"pricingItemId\":10,\"finalRate\":\"3.5\"}"));

        verify(itemFinalizationService).processResolutionCreate(argThat((Map<String, Object> p) ->
                p.containsKey("pricingItemId") && p.containsKey("finalRate")));
    }

    @Test
    void commitmentCreate_delegatesToFinalizationService() {
        CommitmentCreateOutboxHandler handler = new CommitmentCreateOutboxHandler();
        ReflectionTestUtils.setField(handler, "itemFinalizationService", itemFinalizationService);

        handler.handle(event("COMMITMENT_CREATE", "{\"pricingItemId\":10,\"resolutionId\":500}"));

        verify(itemFinalizationService).processCommitmentCreate(anyMap());
    }

    @Test
    void handlerEventTypes_matchOutboxContract() {
        assertEquals("FLOW_START", new FlowStartOutboxHandler().eventType());
        assertEquals("NOTIFY", new NotifyOutboxHandler().eventType());
        assertEquals("RESOLUTION_CREATE", new ResolutionCreateOutboxHandler().eventType());
        assertEquals("COMMITMENT_CREATE", new CommitmentCreateOutboxHandler().eventType());
    }
}
