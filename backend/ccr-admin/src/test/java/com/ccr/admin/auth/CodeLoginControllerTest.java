package com.ccr.admin.auth;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.ccr.admin.config.AuthingCodeIdentityService;
import com.ccr.admin.controller.AuthController;
import com.ccr.admin.controller.dto.CodeLoginRequest;
import com.ccr.admin.system.domain.CcrSysUser;
import com.ccr.common.core.assignee.NodeAssigneeResolver;
import com.ccr.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodeLoginControllerTest {
    @Test void issuesCcrTokenAndPreservesLocalUserContract() {
        var identity = mock(AuthingCodeIdentityService.class);
        var controller = controller(identity);
        var user = new CcrSysUser(); user.setId(42L); user.setUsername("001234");
        user.setRoleCode("branch_manager"); user.setNickName("测试审批人");
        when(identity.verify("code")).thenReturn(user);
        var response = new MockHttpServletResponse();
        try (var stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getSession).thenReturn(mock(SaSession.class));
            stp.when(StpUtil::getTokenValue).thenReturn("ccr-session-token");
            var result = controller.codeLogin(new CodeLoginRequest("code"), new MockHttpServletRequest(), response);
            assertEquals("ccr-session-token", result.getData().get("token"));
            var info = (java.util.Map<?, ?>) result.getData().get("userInfo");
            assertEquals("001234", info.get("userName"));
            assertArrayEquals(new String[]{"branch_manager"}, (String[]) info.get("roles"));
            assertEquals("no-store", response.getHeader("Cache-Control"));
            stp.verify(() -> StpUtil.login(42L));
        }
    }

    @Test void identityFailureNeverCreatesSession() {
        var identity = mock(AuthingCodeIdentityService.class);
        when(identity.verify("invalid")).thenThrow(new ServiceException(401, "认证失败"));
        try (var stp = mockStatic(StpUtil.class)) {
            assertThrows(ServiceException.class, () -> controller(identity).codeLogin(new CodeLoginRequest("invalid"),
                    new MockHttpServletRequest(), new MockHttpServletResponse()));
            stp.verifyNoInteractions();
        }
    }

    private AuthController controller(AuthingCodeIdentityService identity) {
        var controller = new AuthController();
        ReflectionTestUtils.setField(controller, "authingCodeIdentity", identity);
        ReflectionTestUtils.setField(controller, "nodeAssigneeResolver", mock(NodeAssigneeResolver.class));
        ReflectionTestUtils.setField(controller, "jdbcTemplate", mock(JdbcTemplate.class));
        return controller;
    }
}
