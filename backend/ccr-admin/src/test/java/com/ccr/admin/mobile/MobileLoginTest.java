package com.ccr.admin.mobile;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.ccr.admin.controller.AuthController;
import com.ccr.admin.system.domain.CcrSysUser;
import com.ccr.admin.system.mapper.CcrSysUserMapper;
import com.ccr.common.core.assignee.NodeAssigneeResolver;
import com.ccr.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MobileLoginTest {
    @Test void verifiedIdentityCreatesDedicatedMobileSessionWithoutPassword() {
        var identity=mock(YouduIdentityService.class);var users=mock(CcrSysUserMapper.class);var nodes=mock(NodeAssigneeResolver.class);
        var access=new MobileAccessService();ReflectionTestUtils.setField(access,"assignees",nodes);
        var auth=controller(identity,users,nodes,access);
        var user=new CcrSysUser();user.setId(42L);user.setUsername("approver");user.setNickName("测试审批人");user.setRoleCode("branch_manager");user.setStatus("ENABLE");user.setDelFlag("0");
        when(identity.verify("native-token")).thenReturn("approver");when(users.selectOne(any())).thenReturn(user);
        try(var stp=mockStatic(StpUtil.class)){
            var session=mock(SaSession.class);var tokenSession=mock(SaSession.class);
            stp.when(StpUtil::getSession).thenReturn(session);stp.when(StpUtil::getTokenSession).thenReturn(tokenSession);stp.when(StpUtil::getTokenValue).thenReturn("ccr-session");
            var result=auth.mobileLogin("native-token",new MockHttpServletRequest());
            assertEquals("ccr-session",result.getData().get("token"));
            stp.verify(()->StpUtil.login(eq(42L),org.mockito.ArgumentMatchers.<cn.dev33.satoken.stp.SaLoginModel>argThat(model->"mobile".equals(model.getDevice())&&model.getToken()!=null)));
            verify(tokenSession).set("client",MobileAccessService.CLIENT);verify(identity).verify("native-token");
        }
    }
    @Test void invalidPlatformIdentityCannotCreateAnySession() {
        var identity=mock(YouduIdentityService.class);var users=mock(CcrSysUserMapper.class);var nodes=mock(NodeAssigneeResolver.class);
        var auth=controller(identity,users,nodes,new MobileAccessService());
        when(identity.verify("invalid")).thenThrow(new ServiceException(401,"凭证过期"));
        try(var stp=mockStatic(StpUtil.class)){
            assertThrows(ServiceException.class,()->auth.mobileLogin("invalid",new MockHttpServletRequest()));
            verifyNoInteractions(users);stp.verifyNoInteractions();
        }
    }
    @Test void oaUsesVerifiedAccountAndNeverFallsBackToYoudu() {
        var identity=mock(YouduIdentityService.class);var oa=mock(OaIdentityService.class);
        var users=mock(CcrSysUserMapper.class);var nodes=mock(NodeAssigneeResolver.class);
        var access=new MobileAccessService();ReflectionTestUtils.setField(access,"assignees",nodes);
        var auth=controller(identity,users,nodes,access);ReflectionTestUtils.setField(auth,"oaIdentity",oa);
        var user=new CcrSysUser();user.setId(43L);user.setUsername("oa.approver");user.setRoleCode("president");user.setStatus("ENABLE");user.setDelFlag("0");
        when(oa.verify("oa-ticket")).thenReturn("oa.approver");when(users.selectOne(any())).thenReturn(user);
        try(var stp=mockStatic(StpUtil.class)) {
            var session=mock(SaSession.class);stp.when(StpUtil::getSession).thenReturn(session);stp.when(StpUtil::getTokenSession).thenReturn(session);
            stp.when(StpUtil::getTokenValue).thenReturn("mobile-oa-session");
            assertEquals("mobile-oa-session",auth.mobileOaLogin("oa-ticket",new MockHttpServletRequest()).getData().get("token"));
            verify(session).set("client",MobileAccessService.CLIENT);
            user.setRoleCode("customer_manager");
            assertThrows(ServiceException.class,()->auth.mobileOaLogin("oa-ticket",new MockHttpServletRequest()));
            stp.verify(()->StpUtil.login(eq(43L),org.mockito.ArgumentMatchers.<cn.dev33.satoken.stp.SaLoginModel>any()),times(1));
        }
        when(oa.verify("expired")).thenThrow(new ServiceException(401,"票据过期"));
        assertThrows(ServiceException.class,()->auth.mobileOaLogin("expired",new MockHttpServletRequest()));
        verifyNoInteractions(identity);
    }

    private AuthController controller(YouduIdentityService identity,CcrSysUserMapper users,NodeAssigneeResolver nodes,MobileAccessService access){
        var auth=new AuthController();ReflectionTestUtils.setField(auth,"youduIdentity",identity);ReflectionTestUtils.setField(auth,"sysUserMapper",users);
        ReflectionTestUtils.setField(auth,"mobileAccess",access);ReflectionTestUtils.setField(auth,"nodeAssigneeResolver",nodes);ReflectionTestUtils.setField(auth,"jdbcTemplate",mock(JdbcTemplate.class));return auth;
    }
    @Test void messageQueryAlwaysUsesCurrentRecipientAndOmitsOperationalFields(){
        var service=new MobileQueryService();var jdbc=mock(JdbcTemplate.class);ReflectionTestUtils.setField(service,"jdbc",jdbc);
        when(jdbc.queryForList(anyString(),eq("42"))).thenReturn(List.of());assertTrue(service.messages(42L).isEmpty());
        verify(jdbc).queryForList(argThat(sql->sql.contains("recipient_id=?")&&sql.contains("del_flag='0'")&&!sql.contains("error_message")),eq("42"));
        assertThrows(NullPointerException.class,()->service.messages(null));
    }
}
