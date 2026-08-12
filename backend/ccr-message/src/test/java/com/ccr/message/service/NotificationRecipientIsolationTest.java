package com.ccr.message.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ccr.message.domain.CcrNotificationLog;
import com.ccr.message.mapper.CcrNotificationLogMapper;
import com.ccr.message.mapper.CcrNotificationRecipientMapper;
import com.ccr.message.mapper.CcrNotificationRuleMapper;
import com.ccr.message.service.dto.NotificationMessage;
import com.ccr.message.service.impl.NotificationServiceImpl;
import com.ccr.message.service.recipient.RecipientContext;
import com.ccr.message.service.recipient.RecipientResolver;
import com.ccr.message.service.recipient.impl.BranchManagerRecipientResolver;
import com.ccr.message.service.sender.MessageSender;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 支行通知必须携带机构上下文并解析为本支行具体用户。 */
@ExtendWith(MockitoExtension.class)
class NotificationRecipientIsolationTest {

    @Mock
    private CcrNotificationRuleMapper ruleMapper;
    @Mock
    private CcrNotificationRecipientMapper recipientMapper;
    @Mock
    private CcrNotificationLogMapper logMapper;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private RecipientResolver recipientResolver;
    @Mock
    private MessageSender messageSender;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CcrNotificationLog.class);
    }

    @Test
    void sendNotification_resolvesBranchManagerWithinOrgContext() {
        NotificationServiceImpl service = new NotificationServiceImpl();
        ReflectionTestUtils.setField(service, "ruleMapper", ruleMapper);
        ReflectionTestUtils.setField(service, "recipientMapper", recipientMapper);
        ReflectionTestUtils.setField(service, "logMapper", logMapper);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(service, "recipientResolvers", List.of(recipientResolver));
        ReflectionTestUtils.setField(service, "messageSenders", List.of(messageSender));
        ReflectionTestUtils.setField(service, "maxRetry", 3);
        when(recipientResolver.supports("BRANCH_MANAGER")).thenReturn(true);
        when(recipientResolver.resolve(eq("BRANCH_MANAGER"), eq(null), any(RecipientContext.class)))
                .thenReturn(List.of("1001"));
        when(logMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(messageSender.supports("SYSTEM")).thenReturn(true);

        NotificationMessage message = new NotificationMessage();
        message.setRecipientType("BRANCH_MANAGER");
        message.setOrgId(1009L);
        message.setChannel("SYSTEM");
        message.setMessageKey("SUBMIT_NOTIFY:APP:1:BRANCH_MANAGER");
        message.setContent("待审批");

        service.sendNotification(message);

        ArgumentCaptor<RecipientContext> contextCaptor = ArgumentCaptor.forClass(RecipientContext.class);
        verify(recipientResolver).resolve(eq("BRANCH_MANAGER"), eq(null), contextCaptor.capture());
        assertEquals(1009L, contextCaptor.getValue().getOrgId());
        ArgumentCaptor<CcrNotificationLog> logCaptor = ArgumentCaptor.forClass(CcrNotificationLog.class);
        verify(logMapper).insert(logCaptor.capture());
        assertEquals("USER", logCaptor.getValue().getRecipientType());
        assertEquals("1001", logCaptor.getValue().getRecipientId());
        assertEquals("SUBMIT_NOTIFY:APP:1:BRANCH_MANAGER:1001", logCaptor.getValue().getMessageKey());
    }

    @Test
    void branchResolver_usesSourceAndManagerBranchCodes() {
        BranchManagerRecipientResolver resolver = new BranchManagerRecipientResolver();
        ReflectionTestUtils.setField(resolver, "jdbcTemplate", jdbcTemplate);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(1009L)))
                .thenReturn(List.of("1001"));
        RecipientContext context = new RecipientContext();
        context.setOrgId(1009L);

        assertEquals(List.of("1001"), resolver.resolve("BRANCH_MANAGER", null, context));

        verify(jdbcTemplate).queryForList(anyString(), eq(String.class), eq(1009L));
    }

    @Test
    void sendNotification_doesNotResolveAnAlreadyResolvedUserAgain() {
        NotificationServiceImpl service = new NotificationServiceImpl();
        ReflectionTestUtils.setField(service, "ruleMapper", ruleMapper);
        ReflectionTestUtils.setField(service, "recipientMapper", recipientMapper);
        ReflectionTestUtils.setField(service, "logMapper", logMapper);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(service, "recipientResolvers", List.of(recipientResolver));
        ReflectionTestUtils.setField(service, "messageSenders", List.of(messageSender));
        ReflectionTestUtils.setField(service, "maxRetry", 3);
        when(logMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(messageSender.supports("SYSTEM")).thenReturn(true);

        NotificationMessage message = new NotificationMessage();
        message.setRecipientType("BRANCH_MANAGER");
        message.setRecipientId("1001");
        message.setChannel("SYSTEM");
        message.setMessageKey("CCR1-260812-WATCH-1001");
        message.setContent("预警通知");

        service.sendNotification(message);

        verifyNoInteractions(recipientResolver);
        ArgumentCaptor<CcrNotificationLog> logCaptor = ArgumentCaptor.forClass(CcrNotificationLog.class);
        verify(logMapper).insert(logCaptor.capture());
        assertEquals("1001", logCaptor.getValue().getRecipientId());
        assertEquals("BRANCH_MANAGER", logCaptor.getValue().getRecipientType());
    }

    @Test
    void branchResolver_withoutOrgContextReturnsEmpty() {
        BranchManagerRecipientResolver resolver = new BranchManagerRecipientResolver();
        ReflectionTestUtils.setField(resolver, "jdbcTemplate", jdbcTemplate);

        assertEquals(List.of(), resolver.resolve("BRANCH_MANAGER", null, new RecipientContext()));

        verifyNoInteractions(jdbcTemplate);
    }
}
