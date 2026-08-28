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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 普通节点审批单元测试(整单流转口径)
 * 覆盖:节点不符/小组绕过拒绝、存款双轨消除(超上限整单上会)、整单流转(权限内齐套终审/
 * 超权限整单上送/整单否决)、调价越权/硬边界拒绝、版本冲突与任务已处理、幂等键、待办按登录人角色过滤
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

        // 缺省无节点指派配置(解析为空=不限制,保持角色匹配);selectBatchIds 用于待办指派过滤
        org.mockito.Mockito.lenient().when(nodeAssigneeResolver.resolveUserIds(any(), any(), any()))
                .thenReturn(List.of());
        org.mockito.Mockito.lenient().when(applicationMapper.selectBatchIds(any()))
                .thenReturn(List.of(application));
    }

    private SysUserRead user(String roleCode) {
        SysUserRead user = new SysUserRead();
        user.setId(1001L);
        user.setRoleCode(roleCode);
        user.setStatus("ENABLE");
        return user;
    }

    /** 通用打桩:支行行长登录 + 申请/权限边界(贷款下限 3.0);不打桩分项,便于用例指定触发分项 */
    private void stubBranchManagerLoanPerm() {
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_BRANCH_MANAGER));
        when(applicationMapper.selectById(30L)).thenReturn(application);
        CcrNodePermission perm = new CcrNodePermission();
        perm.setNodeCode("BRANCH_MANAGER");
        perm.setBusinessType("LOAN");
        perm.setBoundaryMinRate(new BigDecimal("3.000000"));
        when(nodePermissionMapper.selectOne(any(Wrapper.class))).thenReturn(perm);
    }

    /** 通用打桩:支行行长登录 + 分项/申请/权限边界(贷款下限 3.0) */
    private void stubBranchManagerLoan() {
        stubBranchManagerLoanPerm();
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
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
                () -> approvalService.approve(10L, "SIX_PEOPLE_GROUP", null, null, 3, null, null));
        assertEquals(ErrorCode.NODE_PERMISSION.getCode(), e.getCode());
    }

    @Test
    void approve_reject_whenNodeMismatch() {
        item.setCurrentNodeCode("DEPT_GENERAL_MANAGER");
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_BRANCH_MANAGER));
        when(pricingItemMapper.selectById(10L)).thenReturn(item);

        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(10L, "BRANCH_MANAGER", null, null, 3, null, null));
        assertEquals(ErrorCode.NODE_PERMISSION.getCode(), e.getCode());
        verify(pricingItemMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void approve_reject_whenRoleNotMatchNode() {
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_CUSTOMER_MANAGER));
        // 分项/申请存在,节点角色校验兜底拒绝(§5.5.1 未配置指派→按角色校验)
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(applicationMapper.selectById(30L)).thenReturn(application);
        doThrow(new ServiceException(ErrorCode.NODE_PERMISSION.getCode(), "不具备节点角色"))
                .when(currentLoginUser).requireNodeRole("BRANCH_MANAGER");

        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(10L, "BRANCH_MANAGER", null, null, 3, null, null));
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
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        approvalService.approve(10L, "BRANCH_MANAGER", null, "同意", 3, null, null);

        // 存款期限上限未冻结(boundaryRate 为空)视为超上限 → 整单上会,触发建表决批次
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
                () -> approvalService.approve(10L, "DEPT_GENERAL_MANAGER", null, null, 3, null, null));
        assertEquals(ErrorCode.NODE_PERMISSION.getCode(), e.getCode());
    }

    // ---------- 权限内终审 / 上送(整单流转:单项申请即整单) ----------

    @Test
    void approve_loan_inPermission_terminalAndFinalizes() {
        stubBranchManagerLoan();
        // 单项申请:权限内通过即整单齐套 → 终审
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        approvalService.approve(10L, "BRANCH_MANAGER", null, "同意", 3, null, null);

        // 整单齐套终审 → 决议/承诺/主申请聚合
        verify(itemFinalizationService).afterItemTerminal(10L, "LEVEL_APPROVED");
        verify(voteService, never()).createGroupRound(any());
        verify(approvalActionMapper).insert(argThat((CcrApprovalAction a) -> "APPROVE".equals(a.getActionType())
                && Long.valueOf(1001L).equals(a.getOperatorId())));
    }

    @Test
    void approve_loan_beyondPermission_escalatesToNextNode() {
        stubBranchManagerLoan();
        item.setCurrentApprovalRate(new BigDecimal("2.800000")); // 低于支行下限 3.0
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        approvalService.approve(10L, "BRANCH_MANAGER", null, "超权限保留利率上送", 3, null, null);

        // 超权限保留利率通过 → 整单上送部门总经理,不终审、不合批
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
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        // 分管行长节点需部门归属配置:指派命中操作人(1001)放行,否则 guardNodeAssignee 空归属直接拒绝
        when(nodeAssigneeResolver.resolveUserIds("VICE_PRESIDENT", null, null)).thenReturn(List.of(1001L));

        approvalService.approve(10L, "VICE_PRESIDENT", null, null, 3, null, null);

        // 整单上送终点为六人小组 → 自动合批
        verify(voteService).createGroupRound(30L);
    }

    // ---------- 整单流转(多项申请) ----------

    @Test
    void approve_loan_firstItemAgreed_staysRouting_notFinalized() {
        // 两项申请:第一项权限内通过 → 记「本节点已同意」,保持 ROUTING 在当前节点,暂不终审
        stubBranchManagerLoan();
        CcrPricingItem item2 = siblingItem(11L, "PI002", "3.500000");
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item, item2));
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        approvalService.approve(10L, "BRANCH_MANAGER", null, "同意", 3, null, null);

        verify(itemFinalizationService, never()).afterItemTerminal(any(), any());
        verify(voteService, never()).createGroupRound(any());
        // 仅更新触发分项(保持 ROUTING),留痕 ROUTING→ROUTING 表示本节点已同意
        verify(pricingItemMapper, times(1)).update(isNull(), any(Wrapper.class));
        verify(approvalActionMapper).insert(argThat((CcrApprovalAction a) -> "APPROVE".equals(a.getActionType())
                && PricingItemStatus.ROUTING.getCode().equals(a.getToStatus())));
    }

    @Test
    void approve_loan_secondItemAgreed_allFinalizeTogether() {
        // 两项申请:第一项已本节点同意,第二项权限内通过 → 两项一起终审
        stubBranchManagerLoanPerm();
        CcrPricingItem item2 = siblingItem(11L, "PI002", "3.200000");
        when(pricingItemMapper.selectById(11L)).thenReturn(item2);
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item, item2));
        // 分项 10 已有本节点 APPROVE 动作(本节点已同意)
        CcrApprovalAction prior = new CcrApprovalAction();
        prior.setPricingItemId(10L);
        // APPROVE 查询命中 prior(10L 已本节点同意),REJECT 查询无 → 顺序打桩防单值被 nodeRejectedIds 复用
        when(approvalActionMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(prior))
                .thenReturn(List.of());
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        approvalService.approve(11L, "BRANCH_MANAGER", null, "同意", 1, null, null);

        // 两项一起置 APPROVED_LEVEL(触发分项+随行分项各更新一次),逐项触发终态串联
        verify(pricingItemMapper, times(2)).update(isNull(), any(Wrapper.class));
        verify(itemFinalizationService).afterItemTerminal(10L, "LEVEL_APPROVED");
        verify(itemFinalizationService).afterItemTerminal(11L, "LEVEL_APPROVED");
        verify(voteService, never()).createGroupRound(any());
        // 触发分项 + 随行分项各一条终审留痕(ROUTING→APPROVED_LEVEL)
        verify(approvalActionMapper, times(2)).insert(argThat((CcrApprovalAction a) ->
                PricingItemStatus.APPROVED_LEVEL.getCode().equals(a.getToStatus())));
    }

    @Test
    void approve_loan_oneBeyondPermission_wholeOrderEscalates() {
        // 两项申请:一项超权限保留利率通过 → 逐项模型下第一次 approve 未齐套停留,
        // 齐套后整单上送部门总经理,两项一起推进
        stubBranchManagerLoan();
        item.setCurrentApprovalRate(new BigDecimal("2.800000")); // 低于支行下限 3.0
        CcrPricingItem item2 = siblingItem(11L, "PI002", "3.500000");
        // 随行分项终审岗位在小组(非本节点),支行无权就地终审 → 随整单上送留痕
        item2.setRouteCode("SIX_PEOPLE_GROUP");
        when(pricingItemMapper.selectById(11L)).thenReturn(item2);
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item, item2));
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        // 10L 在第一次 approve 后已本节点同意:第二次 approve 的 APPROVE 查询命中,REJECT 查询空
        CcrApprovalAction prior = new CcrApprovalAction();
        prior.setPricingItemId(10L);
        when(approvalActionMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of())          // 第一次 approve:APPROVE 空
                .thenReturn(List.of())          // 第一次 approve:REJECT 空
                .thenReturn(List.of(prior))     // 第二次 approve:APPROVE 命中 10L
                .thenReturn(List.of());         // 第二次 approve:REJECT 空

        approvalService.approve(10L, "BRANCH_MANAGER", null, "超权限保留利率通过", 3, null, null);
        // 第一项未齐套:记本节点已同意,停留不推进、不合批
        verify(pricingItemMapper, times(1)).update(isNull(), any(Wrapper.class));
        verify(voteService, never()).createGroupRound(any());

        approvalService.approve(11L, "BRANCH_MANAGER", null, "同意", 3, null, null);
        // 齐套:超权限整单上送,两项一起更新推进,不终审、不合批;随行分项留痕注明整单上送
        verify(pricingItemMapper, times(3)).update(isNull(), any(Wrapper.class));
        verify(itemFinalizationService, never()).afterItemTerminal(any(), any());
        verify(voteService, never()).createGroupRound(any());
        verify(approvalActionMapper).insert(argThat((CcrApprovalAction a) ->
                Long.valueOf(10L).equals(a.getPricingItemId())
                        && a.getActionComment() != null && a.getActionComment().contains("整单上送")));
    }

    @Test
    void approve_deposit_oneOverLimit_wholeOrderToGroup() {
        // 存款两项:一项超期限上限 → 逐项模型下第一次 approve 未齐套停留,
        // 齐套后整单上会,两项一起推进小组并合批
        application.setBusinessType("DEPOSIT");
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_BRANCH_MANAGER));
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(nodePermissionMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        item.setBoundaryRate(new BigDecimal("1.000000"));
        item.setCurrentApprovalRate(new BigDecimal("1.500000")); // 超期限上限
        CcrPricingItem item2 = siblingItem(11L, "PI002", "0.900000");
        item2.setBoundaryRate(new BigDecimal("1.000000"));
        when(pricingItemMapper.selectById(11L)).thenReturn(item2);
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item, item2));
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        // 10L 在第一次 approve 后已本节点同意:第二次 approve 的 APPROVE 查询命中,REJECT 查询空
        CcrApprovalAction prior = new CcrApprovalAction();
        prior.setPricingItemId(10L);
        when(approvalActionMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of())          // 第一次 approve:APPROVE 空
                .thenReturn(List.of())          // 第一次 approve:REJECT 空
                .thenReturn(List.of(prior))     // 第二次 approve:APPROVE 命中 10L
                .thenReturn(List.of());         // 第二次 approve:REJECT 空

        approvalService.approve(10L, "BRANCH_MANAGER", null, "同意", 3, null, null);
        // 第一项未齐套:记本节点已同意,停留不合批
        verify(pricingItemMapper, times(1)).update(isNull(), any(Wrapper.class));
        verify(voteService, never()).createGroupRound(any());

        approvalService.approve(11L, "BRANCH_MANAGER", null, "同意", 3, null, null);
        // 齐套:超上限整单上会,两项一起更新,合批
        verify(pricingItemMapper, times(3)).update(isNull(), any(Wrapper.class));
        verify(voteService).createGroupRound(30L);
        verify(itemFinalizationService, never()).afterItemTerminal(any(), any());
    }

    @Test
    void approve_loan_mixedRouteCode_triggerChainEnded_finalizeLocallyAndEscalateSibling() {
        // 混合 route_code 齐套:触发分项 route_code=GM(冻结链[BM,GM]已尽,next=null)利率 3.5 在 GM 权限内(≥3.4),
        // sibling route_code=VP(链[BM,GM,VP])利率 3.6 也权限内但终审岗位≠当前节点 → 整单终审条件
        // (allItemsFinalAtThisNode)不成立,落入上送分支;修复前 next==null 直接抛「冻结链路已尽」,
        // 修复后触发分项就地终审 APPROVED_LEVEL、sibling 沿自身链推进 VICE_PRESIDENT
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_DEPT_GM));
        when(applicationMapper.selectById(30L)).thenReturn(application);
        CcrNodePermission gmPerm = new CcrNodePermission();
        gmPerm.setNodeCode("DEPT_GENERAL_MANAGER");
        gmPerm.setBusinessType("LOAN");
        gmPerm.setBoundaryMinRate(new BigDecimal("3.400000"));
        when(nodePermissionMapper.selectOne(any(Wrapper.class))).thenReturn(gmPerm);
        // 部门归属配置:GM 节点解析命中操作人(1001),否则 guardNodeAssignee 空归属直接拒绝
        when(nodeAssigneeResolver.resolveUserIds("DEPT_GENERAL_MANAGER", null, null)).thenReturn(List.of(1001L));

        item.setCurrentNodeCode("DEPT_GENERAL_MANAGER");
        item.setRouteCode("DEPT_GENERAL_MANAGER");
        item.setRouteChain("[\"BRANCH_MANAGER\",\"DEPT_GENERAL_MANAGER\"]");
        item.setCurrentApprovalRate(new BigDecimal("3.500000"));
        when(pricingItemMapper.selectById(10L)).thenReturn(item);

        CcrPricingItem item2 = siblingItem(11L, "PI002", "3.600000");
        item2.setCurrentNodeCode("DEPT_GENERAL_MANAGER");
        item2.setRouteCode("VICE_PRESIDENT");
        item2.setRouteChain("[\"BRANCH_MANAGER\",\"DEPT_GENERAL_MANAGER\",\"VICE_PRESIDENT\"]");
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item, item2));
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        // sibling 11L 已本节点同意(APPROVE 查询命中),触发分项本次动作即视为已处理;REJECT 查询空
        CcrApprovalAction prior = new CcrApprovalAction();
        prior.setPricingItemId(11L);
        when(approvalActionMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(prior))     // APPROVE:命中 11L(sibling 已同意)
                .thenReturn(List.of());         // REJECT:空

        ApprovalResult result = approvalService.approve(10L, "DEPT_GENERAL_MANAGER", null, "同意", 3, null, null);

        // 触发分项 10L 就地终审 APPROVED_LEVEL + sibling 11L 推进 VICE_PRESIDENT,各更新一次
        verify(pricingItemMapper, times(2)).update(isNull(), any(Wrapper.class));
        verify(itemFinalizationService).afterItemTerminal(10L, "LEVEL_APPROVED");
        verify(itemFinalizationService, never()).afterItemTerminal(eq(11L), any());
        verify(voteService, never()).createGroupRound(any());
        // 触发分项终审留痕(APPROVED_LEVEL)+ sibling 上送留痕(ESCALATE)
        verify(approvalActionMapper).insert(argThat((CcrApprovalAction a) ->
                Long.valueOf(10L).equals(a.getPricingItemId())
                        && PricingItemStatus.APPROVED_LEVEL.getCode().equals(a.getToStatus())));
        verify(approvalActionMapper).insert(argThat((CcrApprovalAction a) ->
                Long.valueOf(11L).equals(a.getPricingItemId())
                        && "ESCALATE".equals(a.getActionType())));
        // 返回值:非终审(申请仍有 VP sibling 待审),推进目标为 sibling 的 VICE_PRESIDENT
        assertFalse(result.isTerminal());
        assertEquals("VICE_PRESIDENT", result.getNextNodeCode());
    }

    @Test
    void approve_loan_triggerChainEnded_andOutOfPermission_rejected() {
        // 触发分项冻结链已尽(route_code=GM)但利率 3.2 低于 GM 下限 3.4(超权限)且无下一节点 → 配置异常,
        // 保持原兜底报错(不置空 currentNodeCode 误判终审)
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_DEPT_GM));
        when(applicationMapper.selectById(30L)).thenReturn(application);
        CcrNodePermission gmPerm = new CcrNodePermission();
        gmPerm.setNodeCode("DEPT_GENERAL_MANAGER");
        gmPerm.setBusinessType("LOAN");
        gmPerm.setBoundaryMinRate(new BigDecimal("3.400000"));
        when(nodePermissionMapper.selectOne(any(Wrapper.class))).thenReturn(gmPerm);
        when(nodeAssigneeResolver.resolveUserIds("DEPT_GENERAL_MANAGER", null, null)).thenReturn(List.of(1001L));

        item.setCurrentNodeCode("DEPT_GENERAL_MANAGER");
        item.setRouteCode("DEPT_GENERAL_MANAGER");
        item.setRouteChain("[\"BRANCH_MANAGER\",\"DEPT_GENERAL_MANAGER\"]");
        item.setCurrentApprovalRate(new BigDecimal("3.200000")); // 超权限(低于下限)
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
        when(approvalActionMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of())          // APPROVE:空
                .thenReturn(List.of());         // REJECT:空

        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(10L, "DEPT_GENERAL_MANAGER", null, "同意", 3, null, null));
        assertEquals(ErrorCode.FLOW_STATUS_CONFLICT.getCode(), e.getCode());
        verify(pricingItemMapper, never()).update(isNull(), any(Wrapper.class));
    }

    // ---------- 调价边界(B07) ----------

    @Test
    void approve_adjustRate_beyondNodePermission_rejected() {
        stubBranchManagerLoan();
        // 调价到 2.5 低于支行下限 3.0:主动调价不得越权
        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(10L, "BRANCH_MANAGER", new BigDecimal("2.500000"), null, 3, null, null));
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
                () -> approvalService.approve(10L, "BRANCH_MANAGER", adjustRate, null, 3, null, null));
        assertEquals(ErrorCode.HARD_BOUNDARY.getCode(), e.getCode());
        verify(pricingItemMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void approve_adjustRate_withinBoundary_savedAndFinalized() {
        stubBranchManagerLoan();
        // 调价后重算矩阵路由:终审岗位仍为本节点(支行),链路上送小组
        RouteResult rr = new RouteResult();
        rr.setFinalNodeCode("BRANCH_MANAGER");
        rr.setRouteChain(List.of("BRANCH_MANAGER", "SIX_PEOPLE_GROUP"));
        when(rateMatrixRouter.calcRoute(any())).thenReturn(rr);
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        approvalService.approve(10L, "BRANCH_MANAGER", new BigDecimal("3.200000"), "让利", 3, "K-1", null);

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
                () -> approvalService.approve(10L, "BRANCH_MANAGER", null, null, 3, "K-1", null));
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
                () -> approvalService.approve(10L, "BRANCH_MANAGER", null, null, 2, null, null));
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
                () -> approvalService.approve(10L, "BRANCH_MANAGER", null, null, 3, null, null));
        assertEquals(ErrorCode.TASK_PROCESSED.getCode(), e.getCode());
    }

    @Test
    void approve_reject_whenVersionMissing() {
        stubBranchManagerLoan();

        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(10L, "BRANCH_MANAGER", null, null, null, null, null));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
    }

    // ---------- 否决(逐项否决模型 2026-08-27:否决与同意同样逐项审批,全齐套后整单分派) ----------

    @Test
    void reject_success_terminalAndAggregates() {
        // 单项申请:否决即整单否决(流程直接结束,主申请聚合否决态),返回 terminal
        stubBranchManagerLoan();
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        ApprovalResult r = approvalService.reject(10L, "BRANCH_MANAGER", "资料不全", 3, null);

        assertTrue(r.isTerminal());
        // 记本节点已否决(ROUTING 停留) + 触发分项置 REJECTED 终态
        verify(pricingItemMapper, times(2)).update(isNull(), any(Wrapper.class));
        verify(approvalActionMapper).insert(argThat((CcrApprovalAction a) -> "REJECT".equals(a.getActionType())
                && PricingItemStatus.ROUTING.getCode().equals(a.getFromStatus())
                && PricingItemStatus.REJECTED.getCode().equals(a.getToStatus())));
        verify(itemFinalizationService).afterItemTerminal(10L, null);
        verify(voteService, never()).createGroupRound(any());
    }

    @Test
    void reject_oneItem_unProcessed_stays() {
        // 两项申请:否决任一分项且其余分项未处理 → 未齐套,记「本节点已否决」停留,不整单否决
        stubBranchManagerLoan();
        CcrPricingItem item2 = siblingItem(11L, "PI002", "3.500000");
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item, item2));
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        ApprovalResult r = approvalService.reject(10L, "BRANCH_MANAGER", "资料不全", 3, null);

        assertEquals("BRANCH_MANAGER", r.getNextNodeCode());
        assertFalse(r.isTerminal());
        // 只记本节点已否决(ROUTING→ROUTING),分项仍停留,不置终态、不聚合
        verify(pricingItemMapper, times(1)).update(isNull(), any(Wrapper.class));
        verify(approvalActionMapper).insert(argThat((CcrApprovalAction a) -> "REJECT".equals(a.getActionType())
                && PricingItemStatus.ROUTING.getCode().equals(a.getToStatus())));
        verify(itemFinalizationService, never()).afterItemTerminal(any(), any());
        verify(voteService, never()).createGroupRound(any());
    }

    @Test
    void reject_allItems_wholeOrderRejected() {
        // 两项申请均已本节点否决(触发项本次否决 + 另一项此前已否决) → 齐套全部否决 → 整单否决,流程直接结束
        stubBranchManagerLoan();
        CcrPricingItem item2 = siblingItem(11L, "PI002", "3.500000");
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item, item2));
        CcrApprovalAction item2Rejected = new CcrApprovalAction();
        item2Rejected.setPricingItemId(11L);
        // APPROVE 查询无记录,REJECT 查询命中 item2(此前已否决)
        when(approvalActionMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of())
                .thenReturn(List.of(item2Rejected));
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        ApprovalResult r = approvalService.reject(10L, "BRANCH_MANAGER", "资料不全", 3, null);

        assertTrue(r.isTerminal());
        // 触发分项置 REJECTED + 已否决 sibling 置 REJECTED 并留痕
        verify(approvalActionMapper).insert(argThat((CcrApprovalAction a) -> "REJECT".equals(a.getActionType())
                && Long.valueOf(10L).equals(a.getPricingItemId())
                && PricingItemStatus.REJECTED.getCode().equals(a.getToStatus())));
        verify(approvalActionMapper).insert(argThat((CcrApprovalAction a) -> "REJECT".equals(a.getActionType())
                && Long.valueOf(11L).equals(a.getPricingItemId())
                && a.getActionComment() != null && a.getActionComment().contains("PI001")));
        verify(itemFinalizationService).afterItemTerminal(10L, null);
        verify(voteService, never()).createGroupRound(any());
    }

    @Test
    void reject_partial_wholeOrderEscalates() {
        // 两项申请:触发分项否决 + 另一项本节点已同意 → 齐套部分否决 → 整单上送,
        // 否决分项置 REJECTED、同意分项(本节点即终审岗位且权限内)就地终审 APPROVED_LEVEL
        stubBranchManagerLoan();
        CcrPricingItem item2 = siblingItem(11L, "PI002", "3.500000");
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item, item2));
        CcrApprovalAction item2Approved = new CcrApprovalAction();
        item2Approved.setPricingItemId(11L);
        // APPROVE 查询命中 item2(此前已同意),REJECT 查询无
        when(approvalActionMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(item2Approved))
                .thenReturn(List.of());
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        ApprovalResult r = approvalService.reject(10L, "BRANCH_MANAGER", "部分否决", 3, null);

        // 同意分项就地终审(route_code 为空=本节点终审岗位,3.5≥支行下限 3.0) → 无推进节点 → terminal
        assertTrue(r.isTerminal());
        // 否决分项(触发)置 REJECTED
        verify(approvalActionMapper).insert(argThat((CcrApprovalAction a) -> "REJECT".equals(a.getActionType())
                && Long.valueOf(10L).equals(a.getPricingItemId())
                && PricingItemStatus.REJECTED.getCode().equals(a.getToStatus())));
        // 同意分项就地终审
        verify(approvalActionMapper).insert(argThat((CcrApprovalAction a) -> "APPROVE".equals(a.getActionType())
                && Long.valueOf(11L).equals(a.getPricingItemId())
                && PricingItemStatus.APPROVED_LEVEL.getCode().equals(a.getToStatus())));
        verify(itemFinalizationService).afterItemTerminal(10L, null);
        verify(itemFinalizationService).afterItemTerminal(11L, "LEVEL_APPROVED");
        verify(voteService, never()).createGroupRound(any());
    }

    @Test
    void reject_blankComment_rejected() {
        // §7.3 普通节点否决原因必填
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_BRANCH_MANAGER));

        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.reject(10L, "BRANCH_MANAGER", "  ", 3, null));
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
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(nodeAssigneeResolver.resolveUserIds("BRANCH_MANAGER", null, null)).thenReturn(List.of(2999L));

        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalService.approve(10L, "BRANCH_MANAGER", null, null, 3, null, null));
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

    // ---------- 秘书岗整单门槛(2026-08-28 用户拍板:整单都过秘书岗,秘书岗只审命中分项) ----------

    @Test
    void approve_loan_anySecretaryGate_wholeOrderToSecretaryNotGroup() {
        // VP 节点齐套整单上送:触发分项未命中秘书岗、sibling 命中 → 整单统一先到秘书岗,
        // 严禁未过秘书岗直接到小组;不合批
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_VICE_PRESIDENT));
        when(applicationMapper.selectById(30L)).thenReturn(application);
        CcrNodePermission perm = new CcrNodePermission();
        perm.setNodeCode("VICE_PRESIDENT");
        perm.setBusinessType("LOAN");
        perm.setBoundaryMinRate(new BigDecimal("3.000000"));
        when(nodePermissionMapper.selectOne(any(Wrapper.class))).thenReturn(perm);
        // VP 为部门类节点:节点指派解析出当前登录人(部门归属通过),否则拒绝
        when(nodeAssigneeResolver.resolveUserIds("VICE_PRESIDENT", null, null)).thenReturn(List.of(1001L));
        // sibling 10L 命中秘书岗(链含 SECRETARY,利率 2.5 超 VP 下界 3.0)
        item.setCurrentNodeCode("VICE_PRESIDENT");
        item.setRouteCode("SIX_PEOPLE_GROUP");
        item.setRouteChain("[\"BRANCH_MANAGER\",\"DEPT_GENERAL_MANAGER\",\"VICE_PRESIDENT\",\"SECRETARY\",\"SIX_PEOPLE_GROUP\"]");
        item.setCurrentApprovalRate(new BigDecimal("2.500000"));
        // 触发分项 11L 未命中秘书岗(链无 SECRETARY,利率 3.5 权限内)
        CcrPricingItem item2 = siblingItem(11L, "PI002", "3.500000");
        item2.setCurrentNodeCode("VICE_PRESIDENT");
        item2.setRouteCode("SIX_PEOPLE_GROUP");
        item2.setRouteChain("[\"BRANCH_MANAGER\",\"DEPT_GENERAL_MANAGER\",\"VICE_PRESIDENT\",\"SIX_PEOPLE_GROUP\"]");
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(pricingItemMapper.selectById(11L)).thenReturn(item2);
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item, item2));
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        // 10L 第一次 approve 后已本节点同意:第二次 approve 的 APPROVE 查询命中
        CcrApprovalAction prior = new CcrApprovalAction();
        prior.setPricingItemId(10L);
        when(approvalActionMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of())          // 第一次 approve:APPROVE 空
                .thenReturn(List.of())          // 第一次 approve:REJECT 空
                .thenReturn(List.of(prior))     // 第二次 approve:APPROVE 命中 10L
                .thenReturn(List.of());         // 第二次 approve:REJECT 空

        // 第一项未齐套:记本节点已同意,停留不推进、不合批
        ApprovalResult first = approvalService.approve(10L, "VICE_PRESIDENT", null, "同意", 3, null, null);
        assertEquals("VICE_PRESIDENT", first.getNextNodeCode());
        verify(pricingItemMapper, times(1)).update(isNull(), any(Wrapper.class));
        verify(voteService, never()).createGroupRound(any());

        // 齐套整单上送:触发分项(11L 未命中)与 sibling(10L 命中)统一到 SECRETARY,不直接到小组、不合批
        ApprovalResult second = approvalService.approve(11L, "VICE_PRESIDENT", null, "同意", 3, null, null);
        assertEquals("SECRETARY", second.getNextNodeCode());
        verify(pricingItemMapper, times(3)).update(isNull(), any(Wrapper.class));
        verify(voteService, never()).createGroupRound(any());
        verify(itemFinalizationService, never()).afterItemTerminal(any(), any());
    }

    @Test
    void approve_secretary_untouchedSiblingPasses_wholeOrderToGroup() {
        // 秘书岗节点:批命中分项(10L)后,未命中分项(11L,链无 SECRETARY)过手放行不阻塞齐套,
        // 整单上送小组并合批——秘书岗只审命中分项,未命中分项整单随行
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_SECRETARY));
        when(applicationMapper.selectById(30L)).thenReturn(application);
        CcrNodePermission perm = new CcrNodePermission();
        perm.setNodeCode("SECRETARY");
        perm.setBusinessType("LOAN");
        perm.setBoundaryMinRate(new BigDecimal("3.000000"));
        when(nodePermissionMapper.selectOne(any(Wrapper.class))).thenReturn(perm);
        item.setCurrentNodeCode("SECRETARY");
        item.setRouteCode("SIX_PEOPLE_GROUP");
        item.setRouteChain("[\"BRANCH_MANAGER\",\"DEPT_GENERAL_MANAGER\",\"VICE_PRESIDENT\",\"SECRETARY\",\"SIX_PEOPLE_GROUP\"]");
        item.setCurrentApprovalRate(new BigDecimal("2.500000")); // 命中秘书岗(秘书岗只审此项)
        CcrPricingItem item2 = siblingItem(11L, "PI002", "3.500000");
        item2.setCurrentNodeCode("SECRETARY");
        item2.setRouteCode("SIX_PEOPLE_GROUP");
        item2.setRouteChain("[\"BRANCH_MANAGER\",\"DEPT_GENERAL_MANAGER\",\"VICE_PRESIDENT\",\"SIX_PEOPLE_GROUP\"]"); // 未命中,仅过手
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item, item2));
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        // 唯一一次 approve:APPROVE 空、REJECT 空 → 10L 触发即齐套(11L 秘书岗过手放行不阻塞)
        when(approvalActionMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of())
                .thenReturn(List.of());

        ApprovalResult res = approvalService.approve(10L, "SECRETARY", null, "同意", 3, null, null);
        // 齐套:10L 整单上送小组;11L 链无 SECRETARY,秘书岗节点按触发分项目标推进到 GROUP,整单一起到小组并合批
        assertEquals("SIX_PEOPLE_GROUP", res.getNextNodeCode());
        verify(pricingItemMapper, times(2)).update(isNull(), any(Wrapper.class));
        verify(voteService).createGroupRound(any());
        verify(itemFinalizationService, never()).afterItemTerminal(any(), any());
        // 两条上送留痕都推进至 VOTING(小组轮次)
        verify(approvalActionMapper, times(2)).insert(argThat((CcrApprovalAction a) ->
                PricingItemStatus.VOTING.getCode().equals(a.getToStatus())));
    }
}
