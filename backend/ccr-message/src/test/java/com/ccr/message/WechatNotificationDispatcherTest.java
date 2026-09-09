package com.ccr.message;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ccr.message.domain.CcrNotificationLog;
import com.ccr.message.mapper.CcrNotificationLogMapper;
import com.ccr.message.service.impl.WechatNotificationDispatcher;
import com.ccr.message.service.sender.MessageSender;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WechatNotificationDispatcherTest {
    @BeforeAll static void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), CcrNotificationLog.class);
    }
    private final CcrNotificationLogMapper logs = mock(CcrNotificationLogMapper.class);
    private final MessageSender sender = mock(MessageSender.class);
    private final WechatNotificationDispatcher dispatcher = new WechatNotificationDispatcher(logs, List.of(sender), 3);

    private CcrNotificationLog message(int attempts) {
        CcrNotificationLog row = new CcrNotificationLog();
        row.setId(1L); row.setChannel("WECHAT"); row.setRetryCount(attempts); row.setMessageKey("NR:test");
        return row;
    }

    @Test void competingOrCompletedClaimDoesNotSend() {
        when(logs.update(isNull(), any(Wrapper.class))).thenReturn(0);
        dispatcher.dispatch(message(0));
        verifyNoInteractions(sender);
    }

    @Test void successfulSendClaimsBeforeHttpAndPersistsSuccessWithAttemptGuard() throws Exception {
        when(logs.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(sender.supports("WECHAT")).thenReturn(true);
        dispatcher.dispatch(message(0));
        var order = inOrder(logs, sender);
        order.verify(logs).update(isNull(), any(Wrapper.class));
        order.verify(sender).supports("WECHAT"); order.verify(sender).send(any());
        ArgumentCaptor<Wrapper<CcrNotificationLog>> result = ArgumentCaptor.forClass(Wrapper.class);
        order.verify(logs).update(isNull(), result.capture());
        String sql = result.getValue().getSqlSegment();
        assertTrue(sql.contains("send_status") && sql.contains("retry_count"));
        assertTrue(result.getValue().getSqlSet().contains("send_time"));
    }

    @Test void failureAndRestartRecoveryConsumePersistedBudget() throws Exception {
        when(logs.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(sender.supports("WECHAT")).thenReturn(true);
        doThrow(new RuntimeException("sensitive-key")).when(sender).send(any());
        dispatcher.dispatch(message(2));
        ArgumentCaptor<Wrapper<CcrNotificationLog>> updates = ArgumentCaptor.forClass(Wrapper.class);
        verify(logs, times(2)).update(isNull(), updates.capture());
        assertFalse(updates.getAllValues().toString().contains("sensitive-key"));
        reset(logs, sender);
        when(logs.update(isNull(), any(Wrapper.class))).thenReturn(1);
        dispatcher.dispatch(message(3));
        verify(logs).update(isNull(), any(Wrapper.class)); verifyNoInteractions(sender);
    }

    @Test void claimIncludesRetryDelayAndProcessingLeaseAndAttemptVersion() {
        dispatcher.dispatch(message(1));
        ArgumentCaptor<Wrapper<CcrNotificationLog>> claim = ArgumentCaptor.forClass(Wrapper.class);
        verify(logs).update(isNull(), claim.capture());
        String sql = claim.getValue().getSqlSegment();
        assertTrue(sql.contains("retry_count") && sql.contains("update_time") && sql.contains("channel"));
        verifyNoInteractions(sender);
    }
}
