package com.ccr.common.outbox;

import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NodeReminderPublisherTest {
    @Test void repeatedTransitionUsesSameOutboxKeyAndDifferentRoundRemainsIndependent() {
        OutboxService outbox = mock(OutboxService.class);
        NodeReminderPublisher publisher = new NodeReminderPublisher(outbox);
        publisher.publish(1L, "SIX_PEOPLE_GROUP", "ROUND:20", 20L, null);
        publisher.publish(1L, "SIX_PEOPLE_GROUP", "ROUND:20", 20L, null);
        publisher.publish(1L, "SIX_PEOPLE_GROUP", "ROUND:21", 21L, null);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(outbox, times(3)).publish(eq(OutboxEventType.NOTIFY), keys.capture(), bodies.capture());
        assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
        assertNotEquals(keys.getAllValues().get(0), keys.getAllValues().get(2));
        var event = JSONUtil.parseObj(bodies.getValue());
        assertEquals("NODE_REMINDER", event.getStr("kind"));
        assertEquals(1L, event.getLong("applicationId"));
        assertEquals(21L, event.getLong("roundId"));
        assertEquals(keys.getValue(), event.getStr("messageKey"));
        assertTrue(keys.getValue().length() + "NOTIFY:".length() <= 64);
    }
}
