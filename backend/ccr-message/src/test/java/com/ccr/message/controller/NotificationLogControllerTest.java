package com.ccr.message.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ccr.message.domain.CcrNotificationLog;
import com.ccr.message.mapper.CcrNotificationLogMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationLogControllerTest {
    @BeforeAll static void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), CcrNotificationLog.class);
    }

    @Test void userCannotQueryOthersAndDoesNotSeeDuplicateWechatDeliveryRecord() {
        NotificationLogController controller = new NotificationLogController();
        CcrNotificationLogMapper logs = mock(CcrNotificationLogMapper.class);
        ReflectionTestUtils.setField(controller, "logMapper", logs);
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::getLoginIdAsString).thenReturn("42");
            controller.list("99", null);
            ArgumentCaptor<LambdaQueryWrapper<CcrNotificationLog>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            verify(logs).selectList(query.capture());
            assertTrue(query.getValue().getSqlSegment().contains("channel"));
            assertTrue(query.getValue().getParamNameValuePairs().containsValue("42"));
            assertFalse(query.getValue().getParamNameValuePairs().containsValue("99"));
            assertTrue(query.getValue().getParamNameValuePairs().containsValue("WECHAT"));
            assertTrue(query.getValue().getParamNameValuePairs().containsValue("NR:%"));
        }
    }

    @Test void adminKeepsAllDeliveryLogsForTroubleshooting() {
        NotificationLogController controller = new NotificationLogController();
        CcrNotificationLogMapper logs = mock(CcrNotificationLogMapper.class);
        ReflectionTestUtils.setField(controller, "logMapper", logs);
        try (var login = mockStatic(StpUtil.class)) {
            login.when(() -> StpUtil.hasRole("admin")).thenReturn(true);
            controller.list("99", "FAILED");
            ArgumentCaptor<LambdaQueryWrapper<CcrNotificationLog>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            verify(logs).selectList(query.capture());
            assertFalse(query.getValue().getSqlSegment().contains("channel"));
            assertTrue(query.getValue().getParamNameValuePairs().containsValue("99"));
            assertTrue(query.getValue().getParamNameValuePairs().containsValue("FAILED"));
        }
    }

    @Test void auditorCannotReadOtherRecipientsThroughLegacyEndpoint() {
        NotificationLogController controller = new NotificationLogController();
        CcrNotificationLogMapper logs = mock(CcrNotificationLogMapper.class);
        ReflectionTestUtils.setField(controller, "logMapper", logs);
        try (var login = mockStatic(StpUtil.class)) {
            login.when(() -> StpUtil.hasRole("auditor")).thenReturn(true);
            login.when(StpUtil::getLoginIdAsString).thenReturn("42");
            controller.list("99", null);
            ArgumentCaptor<LambdaQueryWrapper<CcrNotificationLog>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            verify(logs).selectList(query.capture());
            query.getValue().getSqlSegment();
            assertTrue(query.getValue().getParamNameValuePairs().containsValue("42"));
            assertFalse(query.getValue().getParamNameValuePairs().containsValue("99"));
        }
    }

    @Test void adminQueryDeniedBeforeDatabaseAccess() {
        NotificationLogController controller = new NotificationLogController();
        CcrNotificationLogMapper logs = mock(CcrNotificationLogMapper.class);
        ReflectionTestUtils.setField(controller, "logMapper", logs);
        try (var login = mockStatic(StpUtil.class)) {
            login.when(() -> StpUtil.checkRole("admin")).thenThrow(new SecurityException("forbidden"));
            assertThrows(SecurityException.class, () -> controller.adminPage(new com.ccr.message.service.dto.NotificationLogQuery()));
            verifyNoInteractions(logs);
        }
    }

    @Test void adminQueryUsesRequestedPageAndHasAdminAnnotation() throws Exception {
        NotificationLogController controller = new NotificationLogController();
        CcrNotificationLogMapper logs = mock(CcrNotificationLogMapper.class);
        ReflectionTestUtils.setField(controller, "logMapper", logs);
        var query = new com.ccr.message.service.dto.NotificationLogQuery();
        query.setPageNum(3);
        query.setPageSize(50);
        try (var login = mockStatic(StpUtil.class)) {
            controller.adminPage(query);
            verify(logs).selectAdminPage(argThat(page -> page.getCurrent() == 3 && page.getSize() == 50), same(query));
            login.verify(() -> StpUtil.checkRole("admin"));
        }
        var method = NotificationLogController.class.getMethod("adminPage", query.getClass());
        assertArrayEquals(new String[]{"admin"}, method.getAnnotation(cn.dev33.satoken.annotation.SaCheckRole.class).value());
    }
}
