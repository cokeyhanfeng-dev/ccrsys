package com.ccr.admin.mobile;

import com.ccr.admin.system.domain.CcrSysUser;
import com.ccr.common.core.assignee.NodeAssigneeResolver;
import com.ccr.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MobileSecurityTest {
    @Test void youduRequiresExplicitSuccessAndVerifiedAccount() {
        var p=new YouduProperties();var service=new YouduIdentityService(p,new ObjectMapper());
        assertEquals("approver",service.verifiedAccount(json("{\"status\":{\"code\":0},\"userInfo\":{\"account\":\"approver\"}}")));
        for(String body:new String[]{"{}","{\"userInfo\":{\"account\":\"admin\"}}","{\"status\":{\"code\":1},\"userInfo\":{\"account\":\"admin\"}}","{\"status\":{\"code\":0},\"userInfo\":{\"account\":\"\"}}","{\"status\":{\"code\":\"0\"},\"userInfo\":{\"account\":\"admin\"}}","null","bad"})
            assertThrows(ServiceException.class,()->service.verifiedAccount(json(body)));
        assertThrows(ServiceException.class,()->service.verify("client-token"));
        p.setEnabled(true);assertThrows(ServiceException.class,()->service.verify("client-token"));
        p.setVerifyUrl("file:///etc/passwd?token={token}");assertThrows(ServiceException.class,()->service.verify("client-token"));
        assertThrows(ServiceException.class,()->service.verify(""));
    }
    @Test void customerManagerCannotGainMobileAccessThroughSecondaryAssignment() {
        var service=new MobileAccessService();var assignees=mock(NodeAssigneeResolver.class);
        ReflectionTestUtils.setField(service,"assignees",assignees);
        when(assignees.isUserInAssignees(anyString(),anyLong())).thenReturn(true);
        CcrSysUser user=new CcrSysUser();user.setId(42L);user.setStatus("ENABLE");user.setDelFlag("0");
        for(String role:new String[]{"customer_manager","admin","auditor","contract_operator"}){
            user.setRoleCode(role);assertThrows(ServiceException.class,()->service.requireEligible(user));
        }
        verifyNoInteractions(assignees);
        user.setRoleCode("dept_gm");assertTrue(service.requireEligible(user).contains("secretary"));
        user.setStatus("DISABLE");assertThrows(ServiceException.class,()->service.requireEligible(user));
        assertThrows(ServiceException.class,()->service.requireEligible(null));
    }
    @Test void tokensCannotCrossChannels() {
        assertDoesNotThrow(()->MobileSessionInterceptor.enforceChannel(true,true));
        assertDoesNotThrow(()->MobileSessionInterceptor.enforceChannel(false,false));
        assertThrows(ServiceException.class,()->MobileSessionInterceptor.enforceChannel(true,false));
        assertThrows(ServiceException.class,()->MobileSessionInterceptor.enforceChannel(false,true));
    }
    @Test void bpChangesPreserveOriginalPrecision() {
        assertDoesNotThrow(()->MobileApprovalController.validateBp(new BigDecimal("3.055001"),new BigDecimal("3.065001")));
        assertDoesNotThrow(()->MobileApprovalController.validateBp(new BigDecimal("3.05"),new BigDecimal("3.04")));
        assertThrows(ServiceException.class,()->MobileApprovalController.validateBp(new BigDecimal("3.05"),new BigDecimal("3.055")));
        assertThrows(ServiceException.class,()->MobileApprovalController.validateBp(null,BigDecimal.ONE));
    }
    @Test void requestsRequireIdentityVersionAndBoundedRates() {
        try(var factory=Validation.buildDefaultValidatorFactory()){
            var validator=factory.getValidator();
            assertFalse(validator.validate(new MobileApprovalController.Login("")).isEmpty());
            assertTrue(validator.validate(new MobileApprovalController.Login("platform-token")).isEmpty());
            assertFalse(validator.validate(new MobileApprovalController.Approval(1L,"BRANCH_MANAGER",null,"",null)).isEmpty());
            assertFalse(validator.validate(new MobileApprovalController.Approval(1L,"BRANCH_MANAGER",1,"",Map.of(2L,new BigDecimal("36.01")))).isEmpty());
            assertFalse(validator.validate(new MobileApprovalController.Ballot(1L,"OTHER","")).isEmpty());
        }
    }
    private byte[] json(String body){return body.getBytes(StandardCharsets.UTF_8);}
}
