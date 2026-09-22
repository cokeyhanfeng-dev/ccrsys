package com.ccr.admin.system.service;

import cn.dev33.satoken.stp.StpUtil;
import com.ccr.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OnlineUserKickoutServiceTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final OnlineUserKickoutService service = new OnlineUserKickoutService(jdbc);

    void target() {
        when(jdbc.queryForList(anyString(), eq(42L))).thenReturn(List.of(Map.of("id", 42L)));
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(1L))).thenReturn("测试管理员");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    }
    @Test void administratorKicksAllTerminalsAndAuditsBeforeEffectAndAfterSuccess() {
        target();
        List<String> order = new ArrayList<>();
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
            order.add(inv.<String>getArgument(0).startsWith("INSERT") ? "audit-request" : "audit-result");
            return 1;
        });
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
            stp.when(() -> StpUtil.kickout((Object) 42L)).thenAnswer(inv -> { order.add("kickout"); return null; });
            service.kickout(42L);
            assertEquals(List.of("audit-request", "kickout", "audit-result"), order);
            stp.verify(() -> StpUtil.checkRole("admin"));
            stp.verify(() -> StpUtil.kickout((Object) 42L));
            verify(jdbc).update(startsWith("UPDATE"), contains("已完成"), anyLong());
        }
    }
    @Test void unauthorizedCannotReadDatabaseOrInvalidateSession() {
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.checkRole("admin")).thenThrow(new ServiceException(403, "无权限"));
            assertThrows(ServiceException.class, () -> service.kickout(42L));
            verifyNoInteractions(jdbc);
            stp.verify(() -> StpUtil.kickout(any()), never());
        }
    }
    @Test void currentAdministratorAndInvalidIdsAreRejected() {
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
            for (Long id : Arrays.asList(null, 0L, -1L, 1L)) {
                assertThrows(ServiceException.class, () -> service.kickout(id));
            }
            verifyNoInteractions(jdbc);
            stp.verify(() -> StpUtil.kickout(any()), never());
        }
    }
    @Test void missingUserDoesNotKickOrWriteAudit() {
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
            assertThrows(ServiceException.class, () -> service.kickout(42L));
            verify(jdbc, never()).update(anyString(), any(Object[].class));
            stp.verify(() -> StpUtil.kickout(any()), never());
        }
    }
    @Test void failedInitialAuditPreventsRedisOperation() {
        target();
        when(jdbc.update(startsWith("INSERT"), any(Object[].class))).thenReturn(0);
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
            assertThrows(ServiceException.class, () -> service.kickout(42L));
            stp.verify(() -> StpUtil.kickout(any()), never());
        }
    }
    @Test void redisFailureIsRecordedAndReportedAsUncertainWithoutAutomaticRetry() {
        target();
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
            stp.when(() -> StpUtil.kickout((Object) 42L)).thenThrow(new IllegalStateException("unavailable"));
            var error = assertThrows(ServiceException.class, () -> service.kickout(42L));
            assertTrue(error.getMessage().contains("部分会话可能已失效"));
            verify(jdbc).update(startsWith("UPDATE"), contains("执行异常"), anyLong());
            stp.verify(() -> StpUtil.kickout((Object) 42L), times(1));
        }
    }
    @Test void completedKickWithFailedAuditUpdateReportsActualEffect() {
        target();
        when(jdbc.update(startsWith("UPDATE"), any(Object[].class))).thenReturn(0);
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
            var error = assertThrows(ServiceException.class, () -> service.kickout(42L));
            assertTrue(error.getMessage().startsWith("已执行强制下线"));
            stp.verify(() -> StpUtil.kickout((Object) 42L), times(1));
        }
    }
    @Test void realSaTokenInvalidatesBothDevicesPreservesOtherAccountsAndAllowsRelogin() {
        var logic = new cn.dev33.satoken.stp.StpLogic("kickout-test-" + UUID.randomUUID())
                .setConfig(new cn.dev33.satoken.config.SaTokenConfig().setIsConcurrent(true).setIsShare(false));
        String pc = logic.createLoginSession(42L, new cn.dev33.satoken.stp.SaLoginModel().setDevice("PC"));
        String mobile = logic.createLoginSession(42L, new cn.dev33.satoken.stp.SaLoginModel().setDevice("mobile"));
        String other = logic.createLoginSession(43L, new cn.dev33.satoken.stp.SaLoginModel().setDevice("mobile"));
        try {
            assertNotNull(logic.getLoginIdByToken(pc)); assertNotNull(logic.getLoginIdByToken(mobile));
            logic.kickout(42L);
            assertNull(logic.getLoginIdByToken(pc)); assertNull(logic.getLoginIdByToken(mobile));
            assertNotNull(logic.getLoginIdByToken(other));
            assertDoesNotThrow(() -> logic.kickout(42L)); // 已下线账号重复执行为空操作
            String fresh = logic.createLoginSession(42L, new cn.dev33.satoken.stp.SaLoginModel().setDevice("mobile"));
            assertNotNull(logic.getLoginIdByToken(fresh));
        } finally { logic.logout(42L); logic.logout(43L); }
    }

}
