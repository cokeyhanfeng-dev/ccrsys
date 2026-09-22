package com.ccr.admin.system;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.ccr.admin.system.service.OnlineSessionReader;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OnlineSessionReaderTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final StpLogic logic = mock(StpLogic.class);
    private final OnlineSessionReader reader = new OnlineSessionReader(redis);

    @SuppressWarnings("unchecked")
    private Cursor<String> scan(String... tokens) {
        Cursor<String> cursor = mock(Cursor.class);
        Iterator<String> keys = Arrays.stream(tokens).map(token -> "Authorization:login:token:" + token).iterator();
        when(cursor.hasNext()).thenAnswer(call -> keys.hasNext());
        when(cursor.next()).thenAnswer(call -> keys.next());
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(logic.splicingKeyTokenValue("")).thenReturn("Authorization:login:token:");
        return cursor;
    }

    private void valid(String token, String user) {
        when(logic.getLoginIdByToken(token)).thenReturn(user);
        when(logic.getTokenTimeout(token)).thenReturn(86400L);
        when(logic.getTokenActiveTimeoutByToken(token)).thenReturn(7200L);
    }

    @Test void includesBothClientsAndDeduplicatesScanWithoutRenewingSessions() {
        Cursor<String> cursor = scan("pc-token", "mobile-token", "pc-token");
        valid("pc-token", "42"); valid("mobile-token", "42");
        SaSession metadata = mock(SaSession.class);
        when(metadata.get(OnlineSessionReader.LOGIN_TIME)).thenReturn(1000L);
        when(metadata.get(OnlineSessionReader.LOGIN_IP)).thenReturn("127.0.0.1");
        when(logic.getTokenSessionByToken("pc-token", false)).thenReturn(metadata);
        when(logic.getLoginDeviceByToken("mobile-token")).thenReturn("mobile");
        when(logic.getTokenLastActiveTime("pc-token")).thenReturn(2000L);
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getStpLogic).thenReturn(logic);
            var rows = reader.read();
            assertEquals(2, rows.size());
            assertEquals("PC", rows.get(0).client());
            assertEquals(1000L, rows.get(0).loginTime());
            assertEquals(2000L, rows.get(0).lastAccessTime());
            assertEquals("MOBILE", rows.get(1).client());
            assertNull(rows.get(1).loginTime()); // 旧会话不补造登录时间
            assertNull(rows.get(1).loginIp());
            verify(logic, never()).updateLastActiveToNow(anyString());
            verify(logic, never()).getTokenSessionByToken(anyString(), eq(true));
            verify(metadata, never()).set(anyString(), any());
            verify(redis, never()).keys(anyString());
            verify(cursor).close();
        }
    }

    @Test void excludesExpiredIdleKickedAndConcurrentLogout() {
        scan("expired", "idle", "kicked", "racing", "bad-id");
        valid("expired", "1"); valid("idle", "2"); valid("racing", "3");
        when(logic.getTokenTimeout("expired")).thenReturn(-2L);
        when(logic.getTokenActiveTimeoutByToken("idle")).thenReturn(-2L);
        when(logic.getLoginIdByToken("racing")).thenReturn("3", null);
        when(logic.getLoginIdByToken("bad-id")).thenReturn("invalid");
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getStpLogic).thenReturn(logic);
            assertTrue(reader.read().isEmpty());
        }
    }

    @Test void nonExpiringSessionRemainsValid() {
        scan("permanent"); valid("permanent", "42");
        when(logic.getTokenTimeout("permanent")).thenReturn(-1L);
        when(logic.getTokenActiveTimeoutByToken("permanent")).thenReturn(-1L);
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getStpLogic).thenReturn(logic);
            assertEquals(1, reader.read().size());
        }
    }

    @Test void redisFailureIsNotPresentedAsZeroOnlineUsers() {
        when(logic.splicingKeyTokenValue("")).thenReturn("Authorization:login:token:");
        when(redis.scan(any(ScanOptions.class))).thenThrow(new DataAccessResourceFailureException("test"));
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getStpLogic).thenReturn(logic);
            assertThrows(DataAccessResourceFailureException.class, reader::read);
        }
    }
}
