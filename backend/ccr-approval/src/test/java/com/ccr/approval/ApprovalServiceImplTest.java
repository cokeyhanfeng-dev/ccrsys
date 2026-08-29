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
import com.ccr.approval.dto.ApprovalResult;
import com.ccr.approval.mapper.CcrApprovalActionMapper;
import com.ccr.approval.mapper.CcrRateAdjustmentMapper;
import com.ccr.approval.service.impl.ApprovalServiceImpl;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.rule.domain.CcrNodePermission;
import com.ccr.rule.dto.RouteResult;
import com.ccr.rule.engine.RuleEngine;
import com.ccr.rule.mapper.CcrNodePermissionMapper;
import com.ccr.rule.service.RateMatrixRouter;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 普通节点审批单元测试(整单流转口径 2026-08-29)
 * 覆盖:节点不符/小组绕过拒绝、存款仅支行过手、贷款整单终审(终点=routeCode)/
 * 整单上送(链路中间节点过手)、多分项整单一次推进、调价越权/硬边界拒绝、调价重算整单链、
 * 历史申请整单链为空动态重锚定、整单否决(一次否决即整单 REJECTED)、幂等键、任务已处理、待办按登录人角色过滤
 *
 * 整单交付改造语义:审批一次动作处理整单(同申请全部在途分项一起推进/终审/否决),
 * 贷款整单链 = 在途分项中有效利率最低者(锚定分项)的流程,isFinalNode = 当前节点==整单 routeCode;
 * 否决无部分否决,任一节点一次否决即整单 REJECTED。
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
    @Mock
    private com.ccr.common.core.assignee.NodeAssigneeResolver nodeAssigneeResolver;
    @Mock
    private RateMatrixRouter rateMatrixRouter;

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
        application.setCurrentNodeCode("BRANCH_MANAGER");
        // 默认整单链:贷款要上会的最深链(利率低);用例可覆盖
        application.setRouteCode("SIX_PEOPLE_GROUP");
        application.setRouteChain("[\"BRANCH_MANAGER\",\"DEPT_GENERAL_MANAGER\",\"VICE_PRESIDENT\",\"SIX_PEOPLE_GROUP\"]");

        // 缺省无节点指派配置(解析为空=按角色兜底,但部门类节点缺省拒绝);selectBatchIds 用于待办指派过滤
        lenient().when(nodeAssigneeResolver.resolveUserIds(any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(applicationMapper.selectBatchIds(any()))
                .thenReturn(List.of(application));
        // 整单流转批量更新默认成功(逐项 update);用例可覆盖为 0 触发流转中止
        lenient().when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
    }

    private SysUserRead user(String roleCode) {
        SysUserRead user = new SysUserRead();
        user.setId(1001L);
        user.setRoleCode(roleCode);
        user.setStatus("ENABLE");
        return user;
    }

    /** 通用打桩:支行行长登录 + 申请/权限边界(贷款下限 3.0) */
    private void stubBranchManagerLoanPerm() {
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_BRANCH_MANAGER));
        when(applicationMapper.selectById(30L)).thenReturn(application);
        CcrNodePermission perm = new CcrNodePermission();
        perm.setNodeCode("BRANCH_MANAGER");
        perm.setBusinessType("LOAN");
        perm.setBoundaryMinRate(new BigDecimal("3.000000"));
        when(nodePermissionMapper.selectOne(any(Wrapper.class))).thenReturn(perm);
    }

    /** 通用打桩:支行行长登录 + 单项在途分项(3.5 在支行权限内) */
    private void stubBranchManagerLoan() {
        stubBranchManagerLoanPerm();
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
    }

    /** 构造同申请 ROUTING 在支行节点的第二分项 */
    private CcrPricingItem siblingItem(Long id, String itemNo, String rate) {
        CcrPricingItem sibling = new CcrPricingItem();
        sibling.setId(id);
        sibling.setApplicationId(30L);
        sibling.setPricingItemNo(itemNo);
        sibling.setProductCode("P001");
        sibling.setStatus(PricingItemStatus.ROUTING.getCode());
        sibling.setCurrentNodeCode("BRANCH_MANAGER");
        sibling.setCurrentApprovalRate(new BigDecimal(rate));
        sibling.setVersionNo(1);
        return sibling;
    }

    // ---------- 身份与节点校验 ----------

    @Test
    void approve_reject_whenGroupNodeNotReachable() {
        // 六人小组从普通审批通道移除(小组绕过不可达)
        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(30L, "SIX_PEOPLE_GROUP", null, null, 3, null, null));
        assertEquals(ErrorCode.NODE_PERMISSION.getCode(), e.getCode());
    }

    @Test
    void approve_reject_whenNodeMismatch() {
        // 整单化:审批推进以申请单当前节点为准,与前端节点不符拒绝
        application.setCurrentNodeCode("DEPT_GENERAL_MANAGER");
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_BRANCH_MANAGER));
        when(applicationMapper.selectById(30L)).thenReturn(application);

        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(30L, "BRANCH_MANAGER", null, null, 3, null, null));
        assertEquals(ErrorCode.NODE_PERMISSION.getCode(), e.getCode());
        verify(pricingItemMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void approve_reject_whenRoleNotMatchNode() {
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_CUSTOMER_MANAGER));
        when(applicationMapper.selectById(30L)).thenReturn(application);
        // 未配置指派→按节点角色校验兜底拒绝(§5.5.1)
        doThrow(new ServiceException(ErrorCode.NODE_PERMISSION.getCode(), "不具备节点角色"))
                .when(currentLoginUser).requireNodeRole("BRANCH_MANAGER");

        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(30L, "BRANCH_MANAGER", null, null, 3, null, null));
        assertEquals(ErrorCode.NODE_PERMISSION.getCode(), e.getCode());
        verify(pricingItemMapper, never()).update(isNull(), any(Wrapper.class));
    }

    // ---------- 存款双轨消除(整单:仅支行过手,此后整单上会) ----------

    @Test
    void approve_deposit_branchManagerPass_triggersGroupRound() {
        application.setBusinessType("DEPOSIT");
        application.setRouteCode(null);
        application.setRouteChain(null);
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_BRANCH_MANAGER));
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(nodePermissionMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));

        approvalService.approve(30L, "BRANCH_MANAGER", null, "同意", 3, null, null);

        // 存款仅支行过手:整单上会小组并合批,不终审
        verify(voteService).createGroupRound(30L);
        verify(itemFinalizationService, never()).afterItemTerminal(any(), any());
    }

    @Test
    void approve_deposit_rejectNonBranchNode() {
        application.setBusinessType("DEPOSIT");
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_DEPT_GM));
        when(applicationMapper.selectById(30L)).thenReturn(application);

        // 申请当前节点=支行,且存款仅支行过手 → 部门总经理动作拒绝
        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(30L, "DEPT_GENERAL_MANAGER", null, null, 3, null, null));
        assertEquals(ErrorCode.NODE_PERMISSION.getCode(), e.getCode());
    }

    // ---------- 贷款整单终审 / 上送 ----------

    @Test
    void approve_loan_inPermission_terminalAndFinalizes() {
        // 整单终审岗位=支行(routeCode=BRANCH_MANAGER,链=[支行]) → 单项 3.5 在权限内整单终审
        application.setRouteCode("BRANCH_MANAGER");
        application.setRouteChain("[\"BRANCH_MANAGER\"]");
        stubBranchManagerLoan();

        approvalService.approve(30L, "BRANCH_MANAGER", null, "同意", 3, null, null);

        // 整单终审 → 决议/承诺/主申请聚合
        verify(itemFinalizationService).afterItemTerminal(10L, "LEVEL_APPROVED");
        verify(voteService, never()).createGroupRound(any());
        verify(approvalActionMapper).insert(argThat((CcrApprovalAction a) -> "APPROVE".equals(a.getActionType())
                && Long.valueOf(1001L).equals(a.getOperatorId())));
    }

    @Test
    void approve_loan_beyondPermission_escalatesToNextNode() {
        stubBranchManagerLoan();
        item.setCurrentApprovalRate(new BigDecimal("2.800000")); // 低于支行下限 3.0
        application.setRouteChain("[\"BRANCH_MANAGER\",\"DEPT_GENERAL_MANAGER\",\"VICE_PRESIDENT\",\"SIX_PEOPLE_GROUP\"]");

        approvalService.approve(30L, "BRANCH_MANAGER", null, "超权限保留利率上送", 3, null, null);

        // 超权限保留利率通过 → 整单上送部门总经理,不终审、不合批
        verify(itemFinalizationService, never()).afterItemTerminal(any(), any());
        verify(voteService, never()).createGroupRound(any());
        verify(approvalActionMapper).insert(any(CcrApprovalAction.class));
    }

    @Test
    void approve_loan_escalateToGroup_createsRound() {
        application.setCurrentNodeCode("VICE_PRESIDENT");
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_VICE_PRESIDENT));
        when(applicationMapper.selectById(30L)).thenReturn(application);
        CcrNodePermission perm = new CcrNodePermission();
        perm.setNodeCode("VICE_PRESIDENT");
        perm.setBusinessType("LOAN");
        perm.setBoundaryMinRate(new BigDecimal("3.600000")); // 当前 3.5 低于分管行领导下限
        when(nodePermissionMapper.selectOne(any(Wrapper.class))).thenReturn(perm);
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
        // 分管行长为部门类节点:指派命中操作人(1001)放行,否则空归属拒绝
        when(nodeAssigneeResolver.resolveUserIds("VICE_PRESIDENT", null, null)).thenReturn(List.of(1001L));

        approvalService.approve(30L, "VICE_PRESIDENT", null, null, 3, null, null);

        // 整单上送终点为六人小组 → 自动合批
        verify(voteService).createGroupRound(30L);
    }

    // ---------- 多分项整单(一次动作整单推进,无逐分项分批) ----------

    @Test
    void approve_loan_multiItem_wholeOrderEscalatesTogether() {
        // 两项在途分项:一次整单审批,两项一起推进下一节点(利率 3.2 分项为锚定,沿默认最深链上送)
        stubBranchManagerLoanPerm();
        CcrPricingItem item2 = siblingItem(11L, "PI002", "3.200000"); // 利率更低=锚定分项
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item, item2));

        approvalService.approve(30L, "BRANCH_MANAGER", null, "同意", 3, null, null);

        // 整单一次推进:两项各 update 一次(共 2 次),都推进部门总经理,不终审、不合批
        verify(pricingItemMapper, times(2)).update(isNull(), any(Wrapper.class));
        verify(itemFinalizationService, never()).afterItemTerminal(any(), any());
        verify(voteService, never()).createGroupRound(any());
    }

    @Test
    void approve_loan_multiItem_finalNode_wholeOrderFinalizesTogether() {
        // 整单终审岗位=支行:两项一起 APPROVED_LEVEL,逐项触发终态串联
        stubBranchManagerLoanPerm();
        CcrPricingItem item2 = siblingItem(11L, "PI002", "3.500000"); // 同利率,首个分项为锚定
        application.setRouteCode("BRANCH_MANAGER");
        application.setRouteChain("[\"BRANCH_MANAGER\"]");
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item, item2));

        approvalService.approve(30L, "BRANCH_MANAGER", null, "同意", 3, null, null);

        verify(pricingItemMapper, times(2)).update(isNull(), any(Wrapper.class));
        verify(itemFinalizationService).afterItemTerminal(10L, "LEVEL_APPROVED");
        verify(itemFinalizationService).afterItemTerminal(11L, "LEVEL_APPROVED");
        verify(voteService, never()).createGroupRound(any());
    }

    @Test
    void approve_loan_finalNode_wholeOrderFinalizes_ignoresPermissionAtEndpoint() {
        // 当前节点=整单链终审岗位(routeCode)→整单一起终审交付(到终点一起交付);
        // 即使利率低于节点矩阵下界也不中途截停,终点即终审
        application.setCurrentNodeCode("DEPT_GENERAL_MANAGER");
        application.setRouteCode("DEPT_GENERAL_MANAGER");
        application.setRouteChain("[\"BRANCH_MANAGER\",\"DEPT_GENERAL_MANAGER\"]");
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_DEPT_GM));
        when(applicationMapper.selectById(30L)).thenReturn(application);
        CcrNodePermission gmPerm = new CcrNodePermission();
        gmPerm.setNodeCode("DEPT_GENERAL_MANAGER");
        gmPerm.setBusinessType("LOAN");
        gmPerm.setBoundaryMinRate(new BigDecimal("3.400000"));
        when(nodePermissionMapper.selectOne(any(Wrapper.class))).thenReturn(gmPerm);
        when(nodeAssigneeResolver.resolveUserIds("DEPT_GENERAL_MANAGER", null, null)).thenReturn(List.of(1001L));
        item.setCurrentNodeCode("DEPT_GENERAL_MANAGER");
        item.setCurrentApprovalRate(new BigDecimal("3.200000")); // 低于 GM 下限但节点即终点
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));

        ApprovalResult result = approvalService.approve(30L, "DEPT_GENERAL_MANAGER", null, "同意", 3, null, null);

        assertTrue(result.isTerminal());
        verify(itemFinalizationService).afterItemTerminal(10L, "LEVEL_APPROVED");
    }

    @Test
    void approve_loan_routeChainBlank_rerouteFromAnchor() {
        // 历史申请整单链为空(或未冻结):审批推进时按锚定分项要素重算整单链并刷新申请单(新旧统一)
        application.setRouteChain(null);
        stubBranchManagerLoanPerm();
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
        RouteResult rr = new RouteResult();
        rr.setFinalNodeCode("SIX_PEOPLE_GROUP");
        rr.setRouteChain(List.of("BRANCH_MANAGER", "DEPT_GENERAL_MANAGER", "VICE_PRESIDENT", "SIX_PEOPLE_GROUP"));
        when(rateMatrixRouter.calcRoute(any())).thenReturn(rr);

        approvalService.approve(30L, "BRANCH_MANAGER", null, "同意", 3, null, null);

        // 重锚定后沿新链推进部门总经理,不终审、不合批
        verify(itemFinalizationService, never()).afterItemTerminal(any(), any());
        verify(voteService, never()).createGroupRound(any());
        verify(approvalActionMapper).insert(any(CcrApprovalAction.class));
    }

    // ---------- 调价边界(B07)与调价重算整单链 ----------

    @Test
    void approve_adjustRate_beyondNodePermission_rejected() {
        stubBranchManagerLoan();
        // 调价到 2.5 低于支行下限 3.0:主动调价不得越权
        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(30L, "BRANCH_MANAGER", new BigDecimal("2.500000"), null, 3, null, null));
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
                () -> approvalService.approve(30L, "BRANCH_MANAGER", adjustRate, null, 3, null, null));
        assertEquals(ErrorCode.HARD_BOUNDARY.getCode(), e.getCode());
        verify(pricingItemMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void approve_adjustRate_withinBoundary_savedAndFinalized() {
        stubBranchManagerLoan();
        // 调价后重算矩阵路由:终审岗位仍为本节点(支行) → 整单终审
        RouteResult rr = new RouteResult();
        rr.setFinalNodeCode("BRANCH_MANAGER");
        rr.setRouteChain(List.of("BRANCH_MANAGER"));
        when(rateMatrixRouter.calcRoute(any())).thenReturn(rr);

        approvalService.approve(30L, "BRANCH_MANAGER", new BigDecimal("3.200000"), "让利", 3, "K-1", null);

        verify(ruleEngine).checkHardBoundary("LOAN", "P001", new BigDecimal("3.200000"));
        verify(rateAdjustmentMapper).insert(argThat((CcrRateAdjustment adj) ->
                adj.getAfterRate().compareTo(new BigDecimal("3.200000")) == 0));
        verify(approvalActionMapper).insert(argThat((CcrApprovalAction a) -> "K-1".equals(a.getIdempotencyKey())));
        verify(itemFinalizationService).afterItemTerminal(10L, "LEVEL_APPROVED");
    }

    @Test
    void approve_loan_adjustRate_reroutesChain() {
        // 调价后锚定分项利率变化 → 整单链按新利率重算,沿新链上送(重锚定)
        stubBranchManagerLoan();
        RouteResult rr = new RouteResult();
        rr.setFinalNodeCode("SIX_PEOPLE_GROUP");
        rr.setRouteChain(List.of("BRANCH_MANAGER", "DEPT_GENERAL_MANAGER", "VICE_PRESIDENT", "SIX_PEOPLE_GROUP"));
        when(rateMatrixRouter.calcRoute(any())).thenReturn(rr);

        ApprovalResult res = approvalService.approve(30L, "BRANCH_MANAGER", new BigDecimal("3.200000"), "让利", 3, "K-1", null);

        // 重算后链终点=小组,当前节点=支行非终点 → 整单上送部门总经理
        assertEquals("DEPT_GENERAL_MANAGER", res.getNextNodeCode());
        assertFalse(res.isTerminal());
        verify(itemFinalizationService, never()).afterItemTerminal(any(), any());
    }

    // ---------- 幂等与并发 ----------

    @Test
    void approve_reject_whenIdempotencyKeyRepeated() {
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_BRANCH_MANAGER));
        when(approvalActionMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(30L, "BRANCH_MANAGER", null, null, 3, "K-1", null));
        assertEquals(ErrorCode.IDEMPOTENCY_REPEAT.getCode(), e.getCode());
        verify(pricingItemMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void approve_taskProcessed_whenNodeAlreadyActed() {
        // 整单防重复守卫:本节点已有任一在途分项处理记录(APPROVE/REJECT) → 整单已处理拒绝重复
        stubBranchManagerLoanPerm();
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
        when(approvalActionMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(30L, "BRANCH_MANAGER", null, null, 3, null, null));
        assertEquals(ErrorCode.TASK_PROCESSED.getCode(), e.getCode());
        verify(pricingItemMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void approve_conflict_whenWholeOrderUpdateZeroRow() {
        // 整单批量更新 0 行(分项状态并发变迁) → 整单流转中止,事务回滚
        stubBranchManagerLoanPerm();
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);

        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(30L, "BRANCH_MANAGER", null, null, 3, null, null));
        assertEquals(ErrorCode.FLOW_STATUS_CONFLICT.getCode(), e.getCode());
    }

    // ---------- 整单否决(一次否决即整单 REJECTED,无部分否决) ----------

    @Test
    void reject_success_terminalAndAggregates() {
        // 单项申请:否决即整单否决(流程直接结束,主申请聚合否决态),返回 terminal
        stubBranchManagerLoan();

        ApprovalResult r = approvalService.reject(30L, "BRANCH_MANAGER", "资料不全", 3, null);

        assertTrue(r.isTerminal());
        verify(pricingItemMapper, times(1)).update(isNull(), any(Wrapper.class));
        verify(approvalActionMapper).insert(argThat((CcrApprovalAction a) -> "REJECT".equals(a.getActionType())
                && PricingItemStatus.REJECTED.getCode().equals(a.getToStatus())));
        verify(itemFinalizationService).afterItemTerminal(10L, null);
        verify(voteService, never()).createGroupRound(any());
    }

    @Test
    void reject_twoItems_wholeOrderRejectedTogether() {
        // 两项在途分项:一次否决即整单否决,两项一起置 REJECTED 并逐项聚合
        stubBranchManagerLoanPerm();
        CcrPricingItem item2 = siblingItem(11L, "PI002", "3.500000");
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item, item2));

        ApprovalResult r = approvalService.reject(30L, "BRANCH_MANAGER", "资料不全", 3, null);

        assertTrue(r.isTerminal());
        verify(pricingItemMapper, times(2)).update(isNull(), any(Wrapper.class));
        verify(approvalActionMapper).insert(argThat((CcrApprovalAction a) -> "REJECT".equals(a.getActionType())
                && Long.valueOf(10L).equals(a.getPricingItemId())
                && PricingItemStatus.REJECTED.getCode().equals(a.getToStatus())));
        verify(approvalActionMapper).insert(argThat((CcrApprovalAction a) -> "REJECT".equals(a.getActionType())
                && Long.valueOf(11L).equals(a.getPricingItemId())));
        verify(itemFinalizationService).afterItemTerminal(10L, null);
        verify(itemFinalizationService).afterItemTerminal(11L, null);
        verify(voteService, never()).createGroupRound(any());
    }

    @Test
    void reject_blankComment_rejected() {
        // §7.3 普通节点否决原因必填
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_BRANCH_MANAGER));

        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.reject(30L, "BRANCH_MANAGER", "  ", 3, null));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
        verify(pricingItemMapper, never()).update(isNull(), any(Wrapper.class));
        verify(approvalActionMapper, never()).insert(any(CcrApprovalAction.class));
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

    // ---------- 节点审批人配置(§5.5.1) ----------

    @Test
    void approve_reject_whenNotInNodeAssignees() {
        // 节点配置了指定审批人,当前登录人不在指派范围 → 拒绝(指派校验先于权限边界查询)
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_BRANCH_MANAGER));
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(nodeAssigneeResolver.resolveUserIds("BRANCH_MANAGER", null, null)).thenReturn(List.of(2999L));

        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(30L, "BRANCH_MANAGER", null, null, 3, null, null));
        assertEquals(ErrorCode.NODE_PERMISSION.getCode(), e.getCode());
    }

    @Test
    void listTodo_assigneeConfigured_onlyAssigneeSees() {
        // 节点配置了指定审批人,解析结果不含当前登录人 → 待办不可见
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_BRANCH_MANAGER));
        when(currentLoginUser.nodeOfRole(CurrentLoginUser.ROLE_BRANCH_MANAGER)).thenReturn("BRANCH_MANAGER");
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
        when(nodeAssigneeResolver.resolveUserIds("BRANCH_MANAGER", null, null)).thenReturn(List.of(2999L));

        assertTrue(approvalService.listTodo().isEmpty());
    }

    // ---------- 秘书岗整单门槛(整单化:锚定分项链含 SECRETARY → 整单先到秘书岗) ----------

    @Test
    void approve_loan_anchorChainHasSecretary_wholeOrderToSecretary() {
        // 锚定分项(利率最低)链含 SECRETARY → 整单统一先到秘书岗,严禁未过秘书岗直接到小组,不合批
        application.setCurrentNodeCode("VICE_PRESIDENT");
        application.setRouteCode("SIX_PEOPLE_GROUP");
        application.setRouteChain("[\"BRANCH_MANAGER\",\"DEPT_GENERAL_MANAGER\",\"VICE_PRESIDENT\",\"SECRETARY\",\"SIX_PEOPLE_GROUP\"]");
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_VICE_PRESIDENT));
        when(applicationMapper.selectById(30L)).thenReturn(application);
        CcrNodePermission perm = new CcrNodePermission();
        perm.setNodeCode("VICE_PRESIDENT");
        perm.setBusinessType("LOAN");
        perm.setBoundaryMinRate(new BigDecimal("3.000000"));
        when(nodePermissionMapper.selectOne(any(Wrapper.class))).thenReturn(perm);
        // VP 为部门类节点:指派命中操作人(1001)放行
        when(nodeAssigneeResolver.resolveUserIds("VICE_PRESIDENT", null, null)).thenReturn(List.of(1001L));
        item.setCurrentNodeCode("VICE_PRESIDENT");
        item.setCurrentApprovalRate(new BigDecimal("2.500000")); // 锚定分项(利率最低)命中秘书岗链
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));

        ApprovalResult res = approvalService.approve(30L, "VICE_PRESIDENT", null, "同意", 3, null, null);

        assertEquals("SECRETARY", res.getNextNodeCode());
        verify(voteService, never()).createGroupRound(any());
        verify(itemFinalizationService, never()).afterItemTerminal(any(), any());
    }

    @Test
    void approve_secretary_wholeOrderToGroup() {
        // 秘书岗审批通过 → 整单上送小组并合批(秘书岗批完整单即交付小组)
        application.setCurrentNodeCode("SECRETARY");
        application.setRouteCode("SIX_PEOPLE_GROUP");
        application.setRouteChain("[\"BRANCH_MANAGER\",\"DEPT_GENERAL_MANAGER\",\"VICE_PRESIDENT\",\"SECRETARY\",\"SIX_PEOPLE_GROUP\"]");
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_SECRETARY));
        when(applicationMapper.selectById(30L)).thenReturn(application);
        CcrNodePermission perm = new CcrNodePermission();
        perm.setNodeCode("SECRETARY");
        perm.setBusinessType("LOAN");
        perm.setBoundaryMinRate(new BigDecimal("3.000000"));
        when(nodePermissionMapper.selectOne(any(Wrapper.class))).thenReturn(perm);
        // 秘书岗为固定机构节点:指派命中操作人(1001)放行,否则空归属拒绝
        when(nodeAssigneeResolver.resolveUserIds("SECRETARY", null, null)).thenReturn(List.of(1001L));
        item.setCurrentNodeCode("SECRETARY");
        item.setCurrentApprovalRate(new BigDecimal("2.500000"));
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));

        ApprovalResult res = approvalService.approve(30L, "SECRETARY", null, "同意", 3, null, null);

        assertEquals("SIX_PEOPLE_GROUP", res.getNextNodeCode());
        verify(voteService).createGroupRound(30L);
        verify(itemFinalizationService, never()).afterItemTerminal(any(), any());
    }
}
