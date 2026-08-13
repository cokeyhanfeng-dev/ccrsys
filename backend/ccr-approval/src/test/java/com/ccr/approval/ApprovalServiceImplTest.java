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
        when(approvalActionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(prior));
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
        // 两项申请:一项超权限保留利率通过 → 整单上送,两项一起推进下一节点
        stubBranchManagerLoan();
        item.setCurrentApprovalRate(new BigDecimal("2.800000")); // 低于支行下限 3.0
        CcrPricingItem item2 = siblingItem(11L, "PI002", "3.500000");
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item, item2));
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        approvalService.approve(10L, "BRANCH_MANAGER", null, "超权限保留利率通过", 3, null, null);

        // 两项一起更新(推进部门总经理),不终审、不合批;随行分项留痕注明整单上送
        verify(pricingItemMapper, times(2)).update(isNull(), any(Wrapper.class));
        verify(itemFinalizationService, never()).afterItemTerminal(any(), any());
        verify(voteService, never()).createGroupRound(any());
        verify(approvalActionMapper).insert(argThat((CcrApprovalAction a) ->
                Long.valueOf(11L).equals(a.getPricingItemId())
                        && a.getActionComment() != null && a.getActionComment().contains("整单上送")));
    }

    @Test
    void approve_deposit_oneOverLimit_wholeOrderToGroup() {
        // 存款两项:一项超期限上限 → 整单上会,两项一起推进小组并合批
        application.setBusinessType("DEPOSIT");
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_BRANCH_MANAGER));
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(nodePermissionMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        item.setBoundaryRate(new BigDecimal("1.000000"));
        item.setCurrentApprovalRate(new BigDecimal("1.500000")); // 超期限上限
        CcrPricingItem item2 = siblingItem(11L, "PI002", "0.900000");
        item2.setBoundaryRate(new BigDecimal("1.000000"));
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item, item2));
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        approvalService.approve(10L, "BRANCH_MANAGER", null, "同意", 3, null, null);

        verify(pricingItemMapper, times(2)).update(isNull(), any(Wrapper.class));
        verify(voteService).createGroupRound(30L);
        verify(itemFinalizationService, never()).afterItemTerminal(any(), any());
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

    // ---------- 否决(整单否决) ----------

    @Test
    void reject_success_terminalAndAggregates() {
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_BRANCH_MANAGER));
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(applicationMapper.selectById(30L)).thenReturn(application);
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        approvalService.reject(10L, "BRANCH_MANAGER", "资料不全", 3, null);

        verify(approvalActionMapper).insert(argThat((CcrApprovalAction a) -> "REJECT".equals(a.getActionType())
                && PricingItemStatus.ROUTING.getCode().equals(a.getFromStatus())
                && PricingItemStatus.REJECTED.getCode().equals(a.getToStatus())));
        verify(itemFinalizationService).afterItemTerminal(10L, null);
        verify(voteService, never()).createGroupRound(any());
    }

    @Test
    void reject_oneItem_wholeOrderRejected() {
        // 两项申请:否决任一分项 → 整单否决,两项一起置 REJECTED 并聚合主申请
        when(currentLoginUser.requireCurrentUser()).thenReturn(user(CurrentLoginUser.ROLE_BRANCH_MANAGER));
        when(pricingItemMapper.selectById(10L)).thenReturn(item);
        when(applicationMapper.selectById(30L)).thenReturn(application);
        CcrPricingItem item2 = siblingItem(11L, "PI002", "3.500000");
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item, item2));
        when(pricingItemMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);

        approvalService.reject(10L, "BRANCH_MANAGER", "资料不全", 3, null);

        // 触发分项 + 随行分项均置 REJECTED;随行分项留痕注明触发分项
        verify(pricingItemMapper, times(2)).update(isNull(), any(Wrapper.class));
        verify(approvalActionMapper).insert(argThat((CcrApprovalAction a) -> "REJECT".equals(a.getActionType())
                && Long.valueOf(11L).equals(a.getPricingItemId())
                && a.getActionComment() != null && a.getActionComment().contains("PI001")));
        verify(itemFinalizationService).afterItemTerminal(10L, null);
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
}
