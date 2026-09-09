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
}
