package com.ccr.admin.message;

import cn.hutool.json.JSONObject;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.common.core.assignee.NodeAssigneeResolver;
import com.ccr.common.exception.ServiceException;
import com.ccr.message.service.NotificationService;
import com.ccr.message.service.dto.NotificationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NodeReminderHandlerTest {
    private final CcrApplicationMapper applications = mock(CcrApplicationMapper.class);
    private final NodeAssigneeResolver assignees = mock(NodeAssigneeResolver.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final WechatMessageProperties props = new WechatMessageProperties();
    private final NodeReminderHandler handler = new NodeReminderHandler(applications, assignees, jdbc, notifications, props);
    private final CcrApplication app = new CcrApplication();
    private final JSONObject payload = new JSONObject();

    @BeforeEach void setup() {
        app.setId(1L); app.setApplicationNo("TEST001"); app.setApplicantOrgId(2L); app.setDeptCode("DEPT_TEST");
        when(applications.selectById(1L)).thenReturn(app);
        payload.set("applicationId", 1L).set("nodeCode", "DEPT_GENERAL_MANAGER").set("messageKey", "NODE:test");
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(1L), anyString(), anyString())).thenReturn(1L);
        props.setEnabled(true);
    }

    @Test void resolvesDepartmentAndDeduplicatesUsersWithSeparateStableChannelKeys() {
        when(assignees.resolveUserIds("DEPT_GENERAL_MANAGER", 2L, "DEPT_TEST")).thenReturn(List.of(11L, 11L, 12L));
        handler.handle(payload); handler.handle(payload);
        ArgumentCaptor<NotificationMessage> messages = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(notifications, times(8)).sendNotification(messages.capture());
        List<NotificationMessage> sent = messages.getAllValues();
        assertEquals(List.of("SYSTEM", "WECHAT", "SYSTEM", "WECHAT"), sent.subList(0, 4).stream().map(NotificationMessage::getChannel).toList());
        assertEquals(4, sent.stream().map(NotificationMessage::getMessageKey).distinct().count());
        assertTrue(sent.stream().allMatch(m -> m.getMessageKey().length() <= 64 && m.getContent().contains("部门总经理审批")));
        assertEquals(sent.get(0).getMessageKey(), sent.get(4).getMessageKey());
    }

    @Test void disabledGatewayKeepsSystemReminder() {
        props.setEnabled(false);
        when(assignees.resolveUserIds(anyString(), anyLong(), anyString())).thenReturn(List.of(11L));
        handler.handle(payload);
        verify(notifications).sendNotification(argThat(m -> "SYSTEM".equals(m.getChannel())));
    }

    @Test void missingDepartmentAssigneeFailsWithoutBroadcast() {
        when(assignees.resolveUserIds(anyString(), anyLong(), anyString())).thenReturn(List.of());
        assertThrows(ServiceException.class, () -> handler.handle(payload));
        verifyNoInteractions(notifications);
        verify(jdbc, never()).queryForList(anyString(), eq(Long.class), any());
    }

    @Test void resolverFailureRemainsRetryableAndNeverFallsBack() {
        when(assignees.resolveUserIds(anyString(), anyLong(), anyString())).thenThrow(new ServiceException(503, "配置不可用"));
        assertThrows(ServiceException.class, () -> handler.handle(payload)); verifyNoInteractions(notifications);
    }

    @Test void processedNodeOrDeletedApplicationProducesNoReminder() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(1L), anyString(), anyString())).thenReturn(0L);
        handler.handle(payload); app.setDelFlag("1"); handler.handle(payload);
        verifyNoInteractions(assignees, notifications);
    }

    @Test void votingUsesFrozenPendingAssignmentsAndSubstituteOnly() {
        payload.set("nodeCode", "SIX_PEOPLE_GROUP").set("roundId", 20L).set("userId", 13L);
        when(jdbc.queryForList(contains("a.status = 'PENDING'"), eq(Long.class), eq(1L), eq(20L), eq(13L))).thenReturn(List.of(13L));
        handler.handle(payload);
        verifyNoInteractions(assignees);
        verify(notifications, times(2)).sendNotification(argThat(m -> "13".equals(m.getRecipientId())));
    }

    @Test void parentBranchHasNoGlobalRoleFallback() {
        payload.set("nodeCode", "PARENT_BRANCH_MANAGER");
        when(assignees.resolveUserIds(anyString(), anyLong(), anyString())).thenReturn(List.of());
        assertThrows(ServiceException.class, () -> handler.handle(payload)); verifyNoInteractions(notifications);
    }

    @Test void presidentUsesPendingItemStatusAndAuthorizedRole() {
        payload.set("nodeCode", "PRESIDENT");
        when(jdbc.queryForObject(contains("PRESIDENT_DECISION"), eq(Long.class), eq(1L))).thenReturn(1L);
        when(assignees.resolveUserIds("PRESIDENT", 2L, "DEPT_TEST")).thenReturn(List.of(11L, 12L));
        when(jdbc.queryForList(contains("role_code = ?"), eq(Long.class), eq("president"))).thenReturn(List.of(12L));
        handler.handle(payload);
        verify(notifications, times(2)).sendNotification(argThat(m -> "12".equals(m.getRecipientId())));
    }
    @Test void delayedRoundAndSubstitutionShareSamePerUserKeys() {
        payload.set("nodeCode", "SIX_PEOPLE_GROUP").set("roundId", 20L);
        when(jdbc.queryForList(contains("a.status = 'PENDING'"), eq(Long.class), eq(1L), eq(20L))).thenReturn(List.of(13L));
        when(jdbc.queryForList(contains("a.status = 'PENDING'"), eq(Long.class), eq(1L), eq(20L), eq(13L))).thenReturn(List.of(13L));
        handler.handle(payload);
        payload.set("messageKey", "SUBSTITUTE:test").set("userId", 13L);
        handler.handle(payload);
        ArgumentCaptor<NotificationMessage> messages = ArgumentCaptor.forClass(NotificationMessage.class);
        verify(notifications, times(4)).sendNotification(messages.capture());
        assertEquals(messages.getAllValues().get(0).getMessageKey(), messages.getAllValues().get(2).getMessageKey());
        assertEquals(messages.getAllValues().get(1).getMessageKey(), messages.getAllValues().get(3).getMessageKey());
    }

    @Test void completedSubstituteDoesNotRaiseFalseAlert() {
        payload.set("nodeCode", "SIX_PEOPLE_GROUP").set("roundId", 20L).set("userId", 13L);
        handler.handle(payload);
        verifyNoInteractions(notifications);
    }

    @Test void branchFallbackBindsApplicantBranchPrefixAndNeverQueriesAllManagers() {
        payload.set("nodeCode", "BRANCH_MANAGER"); app.setApplyBranchCode("TEST_BRANCH");
        when(assignees.resolveUserIds("BRANCH_MANAGER", 2L, "DEPT_TEST")).thenReturn(List.of());
        when(jdbc.queryForList(contains("LEFT(?, CHAR_LENGTH(d.branch_code)) = d.branch_code"), eq(Long.class), eq("TEST_BRANCH")))
                .thenReturn(List.of(15L));
        handler.handle(payload);
        verify(notifications, times(2)).sendNotification(argThat(m -> "15".equals(m.getRecipientId())));
    }

}
