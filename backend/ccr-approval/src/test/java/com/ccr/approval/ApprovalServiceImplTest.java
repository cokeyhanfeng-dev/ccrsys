package com.ccr.approval;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.domain.CcrPricingItem;
import com.ccr.application.enums.PricingItemStatus;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.application.mapper.CcrPricingItemMapper;
import com.ccr.approval.domain.CcrApprovalAction;
import com.ccr.approval.domain.CcrRateAdjustment;
import com.ccr.approval.mapper.CcrApprovalActionMapper;
import com.ccr.approval.mapper.CcrRateAdjustmentMapper;
import com.ccr.approval.service.impl.ApprovalServiceImpl;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.rule.domain.CcrNodePermission;
import com.ccr.rule.engine.RuleEngine;
import com.ccr.rule.mapper.CcrNodePermissionMapper;
import com.ccr.vote.read.SysUserRead;
import com.ccr.vote.service.ItemFinalizationService;
import com.ccr.vote.service.VoteService;
import com.ccr.vote.support.CurrentLoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 普通节点审批单元测试
 * 覆盖:节点不符/小组绕过拒绝、存款双轨消除(支行过手→自动合批)、权限内终审→决议串联、
 * 调价越权/硬边界拒绝、版本冲突与任务已处理、幂等键、待办按登录人角色过滤
 */
@ExtendWith(MockitoExtension.class)
class ApprovalServiceImplTest {

    @Mock
    private CcrPricingItemMapper pricingItemMapper;
    @Mock
    private CcrApplicationMapper applicationMapper;
    @Mock
    private CcrApprovalActionMapper approvalActionMapper;
    @Mock
    private CcrRateAdjustmentMapper rateAdjustmentMapper;
    @Mock
    private CcrNodePermissionMapper nodePermissionMapper;
    @Mock
    private RuleEngine ruleEngine;
    @Mock
    private VoteService voteService;
    @Mock
    private ItemFinalizationService itemFinalizationService;
    @Mock
    private CurrentLoginUser currentLoginUser;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private com.ccr.workflow.service.WarmFlowService warmFlowService;

    @InjectMocks
    private ApprovalServiceImpl approvalService;

    private CcrPricingItem item;
    private CcrApplication application;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CcrPricingItem.class);
        TableInfoHelper.initTableInfo(assistant, CcrApplication.class);
        TableInfoHelper.initTableInfo(assistant, CcrApprovalAction.class);
        TableInfoHelper.initTableInfo(assistant, CcrRateAdjustment.class);
        TableInfoHelper.initTableInfo(assistant, CcrNodePermission.class);

        item = new CcrPricingItem();
        item.setId(10L);
        item.setApplicationId(30L);
        item.setPricingItemNo("PI001");
        item.setProductCode("P001");
        item.setStatus(PricingItemStatus.ROUTING.getCode());
        item.setCurrentNodeCode("BRANCH_MANAGER");
        item.setCurrentApprovalRate(new BigDecimal("3.500000"));
        item.setVersionNo(3);

        application = new CcrApplication();
        application.setId(30L);
        application.setBusinessType("LOAN");
    }

    private SysUserRead user(String roleCode) {
        SysUserRead user = new SysUserRead();
        user.setId(1001L);
        user.setRoleCode(roleCode);
        user.setStatus("ENABLE");
        return user;
    }

    /** 通用打桩:支行行长登录 + 分项/申请/权限边界(贷款下限 3.0) */
    private void stubBranchManagerLoan() {
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_BRANCH_MANAGER));
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(applicationMapper.selectById(30L)).thenReturn(application);
        CcrNodePermission perm = new CcrNodePermission();
        perm.setNodeCode("BRANCH_MANAGER");
        perm.setBusinessType("LOAN");
        perm.setBoundaryMinRate(new BigDecimal("3.000000"));
        when(nodePermissionMapper.selectOne(any(Wrapper.class))).thenReturn(perm);
    }

    // ---------- 身份与节点校验 ----------

    @Test
    void approve_reject_whenGroupNodeNotReachable() {
        // 六人小组从普通审批通道移除(小组绕过不可达)
        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(10L, "SIX_PEOPLE_GROUP", null, null, 3, null));
        assertEquals(ErrorCode.NODE_PERMISSION.getCode(), e.getCode());
    }

    @Test
    void approve_reject_whenNodeMismatch() {
        item.setCurrentNodeCode("DEPT_GENERAL_MANAGER");
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_BRANCH_MANAGER));
        when(pricingItemMapper.selectById(10L)).thenReturn(item);

        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(10L, "BRANCH_MANAGER", null, null, 3, null));
        assertEquals(ErrorCode.NODE_PERMISSION.getCode(), e.getCode());
        verify(pricingItemMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void approve_reject_whenRoleNotMatchNode() {
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_CUSTOMER_MANAGER));
        doThrow(new ServiceException(ErrorCode.NODE_PERMISSION.getCode(), "不具备节点角色"))
                .when(currentLoginUser).requireNodeRole("BRANCH_MANAGER");

        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(10L, "BRANCH_MANAGER", null, null, 3, null));
        assertEquals(ErrorCode.NODE_PERMISSION.getCode(), e.getCode());
        verify(pricingItemMapper, never()).update(isNull(), any(Wrapper.class));
    }

    // ---------- 存款双轨消除 ----------

    @Test
    void approve_deposit_branchManagerPass_triggersGroupRound() {
        application.setBusinessType("DEPOSIT");
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_BRANCH_MANAGER));
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(nodePermissionMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        approvalService.approve(10L, "BRANCH_MANAGER", null, "同意", 3, null);

        // 存款支行过手后直接触发建表决批次,不走权限内终审
        verify(voteService).createGroupRound(30L);
        verify(itemFinalizationService, never()).afterItemTerminal(any(), any());
    }

    @Test
    void approve_deposit_rejectNonBranchNode() {
        application.setBusinessType("DEPOSIT");
        item.setCurrentNodeCode("DEPT_GENERAL_MANAGER");
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_DEPT_GM));
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(applicationMapper.selectById(30L)).thenReturn(application);

        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(10L, "DEPT_GENERAL_MANAGER", null, null, 3, null));
        assertEquals(ErrorCode.NODE_PERMISSION.getCode(), e.getCode());
    }

    // ---------- 权限内终审 / 上送 ----------

    @Test
    void approve_loan_inPermission_terminalAndFinalizes() {
        stubBranchManagerLoan();
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        approvalService.approve(10L, "BRANCH_MANAGER", null, "同意", 3, null);

        // 权限内终审 → 决议/承诺/主申请聚合
        verify(itemFinalizationService).afterItemTerminal(10L, "LEVEL_APPROVED");
        verify(voteService, never()).createGroupRound(any());
        verify(approvalActionMapper).insert(argThat((CcrApprovalAction a) -> "APPROVE".equals(a.getActionType())
                && Long.valueOf(1001L).equals(a.getOperatorId())));
    }

    @Test
    void approve_loan_beyondPermission_escalatesToNextNode() {
        stubBranchManagerLoan();
        item.setCurrentApprovalRate(new BigDecimal("2.800000")); // 低于支行下限 3.0
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        approvalService.approve(10L, "BRANCH_MANAGER", null, "超权限保留利率上送", 3, null);

        // 超权限保留利率通过 → 上送部门总经理,不终审、不合批
        verify(itemFinalizationService, never()).afterItemTerminal(any(), any());
        verify(voteService, never()).createGroupRound(any());
        verify(approvalActionMapper).insert(any(CcrApprovalAction.class));
    }

    @Test
    void approve_loan_escalateToGroup_createsRound() {
        item.setCurrentNodeCode("VICE_PRESIDENT");
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_VICE_PRESIDENT));
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(applicationMapper.selectById(30L)).thenReturn(application);
        CcrNodePermission perm = new CcrNodePermission();
        perm.setNodeCode("VICE_PRESIDENT");
        perm.setBusinessType("LOAN");
        perm.setBoundaryMinRate(new BigDecimal("3.600000")); // 当前 3.5 低于分管行领导下限
        when(nodePermissionMapper.selectOne(any(Wrapper.class))).thenReturn(perm);
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        approvalService.approve(10L, "VICE_PRESIDENT", null, null, 3, null);

        // 上送终点为六人小组 → 自动合批
        verify(voteService).createGroupRound(30L);
    }

    // ---------- 调价边界(B07) ----------

    @Test
    void approve_adjustRate_beyondNodePermission_rejected() {
        stubBranchManagerLoan();
        // 调价到 2.5 低于支行下限 3.0:主动调价不得越权
        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(10L, "BRANCH_MANAGER", new BigDecimal("2.500000"), null, 3, null));
        assertEquals(ErrorCode.NODE_PERMISSION.getCode(), e.getCode());
        verify(ruleEngine, never()).checkHardBoundary(any(), any(), any());
        verify(pricingItemMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void approve_adjustRate_breaksHardBoundary_rejected() {
        stubBranchManagerLoan();
        BigDecimal adjustRate = new BigDecimal("3.200000");
        when(ruleEngine.checkHardBoundary("LOAN", "P001", adjustRate))
                .thenThrow(new ServiceException(ErrorCode.HARD_BOUNDARY.getCode(), "突破业务硬边界"));

        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(10L, "BRANCH_MANAGER", adjustRate, null, 3, null));
        assertEquals(ErrorCode.HARD_BOUNDARY.getCode(), e.getCode());
        verify(pricingItemMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void approve_adjustRate_withinBoundary_savedAndFinalized() {
        stubBranchManagerLoan();
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        approvalService.approve(10L, "BRANCH_MANAGER", new BigDecimal("3.200000"), "让利", 3, "K-1");

        verify(ruleEngine).checkHardBoundary("LOAN", "P001", new BigDecimal("3.200000"));
        verify(rateAdjustmentMapper).insert(argThat((CcrRateAdjustment adj) ->
                adj.getAfterRate().compareTo(new BigDecimal("3.200000")) == 0));
        verify(approvalActionMapper).insert(argThat((CcrApprovalAction a) -> "K-1".equals(a.getIdempotencyKey())));
        verify(itemFinalizationService).afterItemTerminal(10L, "LEVEL_APPROVED");
    }

    // ---------- 幂等与并发 ----------

    @Test
    void approve_reject_whenIdempotencyKeyRepeated() {
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_BRANCH_MANAGER));
        when(approvalActionMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(10L, "BRANCH_MANAGER", null, null, 3, "K-1"));
        assertEquals(ErrorCode.IDEMPOTENCY_REPEAT.getCode(), e.getCode());
        verify(pricingItemMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void approve_versionConflict_whenUpdateZeroRow() {
        stubBranchManagerLoan();
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);
        // 竞态后分项仍为 ROUTING → 版本冲突
        when(pricingItemMapper.selectById(10L)).thenReturn(item, item);

        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(10L, "BRANCH_MANAGER", null, null, 2, null));
        assertEquals(ErrorCode.DATA_VERSION_CONFLICT.getCode(), e.getCode());
    }

    @Test
    void approve_taskProcessed_whenStatusMoved() {
        stubBranchManagerLoan();
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);
        CcrPricingItem moved = new CcrPricingItem();
        moved.setId(10L);
        moved.setStatus(PricingItemStatus.APPROVED_LEVEL.getCode());
        when(pricingItemMapper.selectById(10L)).thenReturn(item, moved);

        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(10L, "BRANCH_MANAGER", null, null, 3, null));
        assertEquals(ErrorCode.TASK_PROCESSED.getCode(), e.getCode());
    }

    @Test
    void approve_reject_whenVersionMissing() {
        stubBranchManagerLoan();

        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(10L, "BRANCH_MANAGER", null, null, null, null));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
    }

    // ---------- 否决 ----------

    @Test
    void reject_success_terminalAndAggregates() {
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_BRANCH_MANAGER));
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        approvalService.reject(10L, "BRANCH_MANAGER", "资料不全", 3, null);

        verify(approvalActionMapper).insert(argThat((CcrApprovalAction a) -> "REJECT".equals(a.getActionType())));
        verify(itemFinalizationService).afterItemTerminal(10L, null);
        verify(voteService, never()).createGroupRound(any());
    }

    // ---------- 待办按登录人角色过滤 ----------

    @Test
    void listTodo_adminSeesAll() {
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_ADMIN));
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));

        List<CcrPricingItem> todos = approvalService.listTodo();
        assertEquals(1, todos.size());
    }

    @Test
    void listTodo_branchManagerFilteredByNode() {
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_BRANCH_MANAGER));
        when(currentLoginUser.nodeOfRole(CurrentLoginUser.ROLE_BRANCH_MANAGER)).thenReturn("BRANCH_MANAGER");
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));

        List<CcrPricingItem> todos = approvalService.listTodo();
        assertEquals(1, todos.size());
        verify(pricingItemMapper).selectList(any(Wrapper.class));
    }

    @Test
    void listTodo_customerManagerSeesNothing() {
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_CUSTOMER_MANAGER));
        when(currentLoginUser.nodeOfRole(CurrentLoginUser.ROLE_CUSTOMER_MANAGER)).thenReturn(null);

        assertTrue(approvalService.listTodo().isEmpty());
        verify(pricingItemMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    void listTodo_committeeMemberUsesVoteTodo() {
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_COMMITTEE));
        when(currentLoginUser.nodeOfRole(CurrentLoginUser.ROLE_COMMITTEE)).thenReturn("SIX_PEOPLE_GROUP");

        // 小组待办在表决模块,普通审批待办为空
        assertTrue(approvalService.listTodo().isEmpty());
        verify(pricingItemMapper, never()).selectList(any(Wrapper.class));
    }
}
