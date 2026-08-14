package com.ccr.application;

import com.ccr.application.domain.CcrApplication;
import com.ccr.application.domain.CcrPricingItem;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.application.mapper.CcrPricingItemMapper;
import com.ccr.application.read.SysUserRead;
import com.ccr.application.service.ApplicationAccessService;
import com.ccr.application.support.AppLoginUser;
import com.ccr.common.core.assignee.NodeAssigneeResolver;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 申请对象授权回归测试：覆盖本人/他人、支行范围、审批任务、委员分配与全量角色。
 */
@ExtendWith(MockitoExtension.class)
class ApplicationAccessServiceTest {

    @Mock
    private AppLoginUser appLoginUser;
    @Mock
    private CcrApplicationMapper applicationMapper;
    @Mock
    private CcrPricingItemMapper pricingItemMapper;
    @Mock
    private NodeAssigneeResolver nodeAssigneeResolver;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private ApplicationAccessService accessService;

    private CcrApplication application;

    @BeforeEach
    void setUp() {
        application = new CcrApplication();
        application.setId(30L);
        application.setApplicantUserId(1000L);
        application.setApplicantOrgId(1001L);
        application.setApplyBranchCode("3202233050");
        application.setStatus("DRAFT");
        when(applicationMapper.selectById(30L)).thenReturn(application);
    }

    @Test
    void requireOwner_allowsApplicantCustomerManager() {
        when(appLoginUser.requireCurrentUser()).thenReturn(user(1000L, AppLoginUser.ROLE_CUSTOMER_MANAGER, 1001L));

        assertDoesNotThrow(() -> accessService.requireOwner(30L));
    }

    @Test
    void requireOwner_rejectsOtherCustomerManager() {
        when(appLoginUser.requireCurrentUser()).thenReturn(user(1009L, AppLoginUser.ROLE_CUSTOMER_MANAGER, 1007L));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> accessService.requireOwner(30L));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), exception.getCode());
    }

    @Test
    void requireDraftOwner_rejectsSubmittedApplication() {
        application.setStatus("ROUTING");
        when(appLoginUser.requireCurrentUser()).thenReturn(user(1000L, AppLoginUser.ROLE_CUSTOMER_MANAGER, 1001L));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> accessService.requireDraftOwner(30L));
        assertEquals(ErrorCode.FLOW_STATUS_CONFLICT.getCode(), exception.getCode());
    }

    @Test
    void requireView_allowsSameBranchManager() {
        when(appLoginUser.requireCurrentUser()).thenReturn(user(1001L, AppLoginUser.ROLE_BRANCH_MANAGER, 1001L));
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(1001L)))
                .thenReturn(List.of("3202233050"));

        assertDoesNotThrow(() -> accessService.requireView(30L));
    }

    @Test
    void requireView_rejectsDifferentBranchManager() {
        when(appLoginUser.requireCurrentUser()).thenReturn(user(1008L, AppLoginUser.ROLE_BRANCH_MANAGER, 1007L));
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(1007L)))
                .thenReturn(List.of("3202233001"));

        ServiceException exception = assertThrows(ServiceException.class,
                () -> accessService.requireView(30L));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), exception.getCode());
    }

    @Test
    void requirePricingItemView_allowsAssignedDepartmentManager() {
        CcrPricingItem item = item("DEPT_GENERAL_MANAGER", "3202233912");
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(appLoginUser.requireCurrentUser()).thenReturn(user(1010L, AppLoginUser.ROLE_DEPT_GM, 1003L));
        when(nodeAssigneeResolver.resolveUserIds("DEPT_GENERAL_MANAGER", 1001L, "3202233912"))
                .thenReturn(List.of(1010L));

        assertDoesNotThrow(() -> accessService.requirePricingItemView(10L));
    }

    @Test
    void requirePricingItemView_rejectsUnassignedDepartmentManager() {
        CcrPricingItem item = item("DEPT_GENERAL_MANAGER", "3202233912");
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(appLoginUser.requireCurrentUser()).thenReturn(user(1011L, AppLoginUser.ROLE_DEPT_GM, 1003L));
        when(nodeAssigneeResolver.resolveUserIds("DEPT_GENERAL_MANAGER", 1001L, "3202233912"))
                .thenReturn(List.of(1010L));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> accessService.requirePricingItemView(10L));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), exception.getCode());
    }

    @Test
    void requireView_allowsCommitteeMemberWithAssignment() {
        when(appLoginUser.requireCurrentUser()).thenReturn(user(1002L, AppLoginUser.ROLE_COMMITTEE_MEMBER, 1003L));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);

        assertDoesNotThrow(() -> accessService.requireView(30L));
    }

    @Test
    void requireView_allowsAuditor() {
        when(appLoginUser.requireCurrentUser()).thenReturn(user(1014L, AppLoginUser.ROLE_AUDITOR, 1000L));

        assertDoesNotThrow(() -> accessService.requireView(30L));
        verify(applicationMapper).selectById(30L);
    }

    @Test
    void requireView_allowsContractOperatorForIssuedResolution() {
        when(appLoginUser.requireCurrentUser()).thenReturn(
                user(1016L, AppLoginUser.ROLE_CONTRACT_OPERATOR, 1000L));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);

        assertDoesNotThrow(() -> accessService.requireView(30L));
    }

    private CcrPricingItem item(String nodeCode, String deptCode) {
        CcrPricingItem item = new CcrPricingItem();
        item.setId(10L);
        item.setApplicationId(30L);
        item.setCurrentNodeCode(nodeCode);
        item.setDeptCode(deptCode);
        return item;
    }

    private SysUserRead user(Long id, String roleCode, Long orgId) {
        SysUserRead user = new SysUserRead();
        user.setId(id);
        user.setRoleCode(roleCode);
        user.setOrgId(orgId);
        return user;
    }
}
