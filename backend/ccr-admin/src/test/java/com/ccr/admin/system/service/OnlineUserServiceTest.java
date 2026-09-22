package com.ccr.admin.system.service;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.ccr.admin.system.controller.OnlineUserController;
import com.ccr.admin.system.dto.OnlineUserQuery;
import com.ccr.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OnlineUserServiceTest {
    private final OnlineSessionReader sessions = mock(OnlineSessionReader.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OnlineUserService service = new OnlineUserService(sessions, jdbc);

    @SuppressWarnings("unchecked")
    private void fixtures() {
        when(sessions.read()).thenReturn(List.of(
                new OnlineSessionReader.Session(42, "PC", 1000L, 3000L, "127.0.0.1"),
                new OnlineSessionReader.Session(42, "MOBILE", 2000L, 4000L, "127.0.0.2"),
                new OnlineSessionReader.Session(43, "PC", null, null, null),
                new OnlineSessionReader.Session(99, "PC", null, null, null)));
        doReturn(List.of(new OnlineUserService.Profile(42, "T042", "测试甲", 10L, "测试机构甲", "贷审会秘书岗"),
                new OnlineUserService.Profile(43, "T043", "测试乙", 20L, "测试机构乙", "部门总经理")))
                .when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test void countsDistinctUsersAndPaginatesFilteredSessionsWithoutCredentials() throws Exception {
        fixtures();
        try (var stp = mockStatic(StpUtil.class)) {
            var query = new OnlineUserQuery(); query.setPageSize(1);
            var result = service.list(query);
            assertEquals(3, result.total()); assertEquals(2, result.userCount());
            assertEquals("MOBILE", result.records().get(0).client());
            query.setPageNum(2);
            assertEquals("PC", service.list(query).records().get(0).client());
            query.setPageNum(Integer.MAX_VALUE);
            assertTrue(service.list(query).records().isEmpty());
            String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
            assertFalse(json.contains("token")); assertFalse(json.contains("password")); assertFalse(json.contains("sessionId"));
            stp.verify(() -> StpUtil.checkRole("admin"), times(3));
            verify(jdbc, atLeastOnce()).query(contains("u.status = 'ENABLE'"), any(RowMapper.class), any(Object[].class));
        }
    }

    @Test void filtersByPersonOrganizationAndClientTogether() {
        fixtures();
        try (var stp = mockStatic(StpUtil.class)) {
            var query = new OnlineUserQuery(); query.setKeyword(" t042 "); query.setOrgId(10L); query.setClient("PC");
            var result = service.list(query);
            assertEquals(1, result.total()); assertEquals(1, result.userCount());
            assertEquals("42", result.records().get(0).userId());
            query.setOrgId(20L);
            assertEquals(0, service.list(query).total());
            query.setOrgId(null); query.setKeyword("测试乙");
            assertEquals("43", service.list(query).records().get(0).userId());
        }
    }

    @Test void unauthorizedRequestRejectedBeforeReadingRedisOrDatabase() {
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.checkRole("admin")).thenThrow(new ServiceException(403, "无权限"));
            assertThrows(ServiceException.class, () -> service.list(new OnlineUserQuery()));
            verifyNoInteractions(sessions, jdbc);
        }
        assertArrayEquals(new String[]{"admin"}, OnlineUserController.class.getAnnotation(SaCheckRole.class).value());
    }

    @Test void emptySessionsAvoidDatabaseQueryAndResponseCannotBeCached() {
        when(sessions.read()).thenReturn(List.of());
        try (var stp = mockStatic(StpUtil.class)) {
            var response = new MockHttpServletResponse();
            var data = new OnlineUserController(service).list(new OnlineUserQuery(), response).getData();
            assertEquals(0, data.total()); assertEquals(0, data.userCount());
            assertEquals("no-store", response.getHeader("Cache-Control"));
            verifyNoInteractions(jdbc);
        }
    }

    @Test void rejectsInvalidPaginationAndFilterInput() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(new OnlineUserQuery()).isEmpty());
            var query = new OnlineUserQuery(); query.setPageNum(0); query.setPageSize(101);
            query.setKeyword("x".repeat(65)); query.setOrgId(-1L); query.setClient("OTHER");
            assertEquals(5, validator.validate(query).size());
        }
    }
}
