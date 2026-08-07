package com.ccr.vote;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ccr.application.domain.CcrPricingItem;
import com.ccr.application.enums.PricingItemStatus;
import com.ccr.application.mapper.CcrPricingItemMapper;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.vote.domain.CcrApprovalActionTrail;
import com.ccr.vote.domain.CcrBallot;
import com.ccr.vote.domain.CcrPresidentDecision;
import com.ccr.vote.domain.CcrVoteAssignment;
import com.ccr.vote.domain.CcrVoteResult;
import com.ccr.vote.domain.CcrVoteRound;
import com.ccr.vote.domain.CcrVoteRoundItem;
import com.ccr.vote.mapper.CcrBallotMapper;
import com.ccr.vote.mapper.CcrPresidentDecisionMapper;
import com.ccr.vote.mapper.CcrVoteAssignmentMapper;
import com.ccr.vote.mapper.CcrVoteResultMapper;
import com.ccr.vote.mapper.CcrVoteRoundItemMapper;
import com.ccr.vote.mapper.CcrVoteRoundMapper;
import com.ccr.vote.read.SysUserRead;
import com.ccr.vote.mapper.SysUserReadMapper;
import com.ccr.vote.service.ItemFinalizationService;
import com.ccr.vote.service.impl.VoteServiceImpl;
import com.ccr.vote.support.CurrentLoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 六人表决与行长决策单元测试
 * 覆盖:身份伪造拒绝(非委员/被替补)、分项批次校验、多分项批次不提前关闭、
 * 计票通过→PRESIDENT_DECISION、行长决策状态守卫与重复决策、替补流程、自动合批
 */
@ExtendWith(MockitoExtension.class)
class VoteServiceImplTest {

    @Mock
    private CcrVoteRoundMapper voteRoundMapper;
    @Mock
    private CcrVoteRoundItemMapper roundItemMapper;
    @Mock
    private CcrVoteAssignmentMapper assignmentMapper;
    @Mock
    private CcrBallotMapper ballotMapper;
    @Mock
    private CcrVoteResultMapper voteResultMapper;
    @Mock
    private CcrPresidentDecisionMapper presidentDecisionMapper;
    @Mock
    private CcrPricingItemMapper pricingItemMapper;
    @Mock
    private SysUserReadMapper sysUserReadMapper;
    @Mock
    private CurrentLoginUser currentLoginUser;
    @Mock
    private ItemFinalizationService itemFinalizationService;
    @Mock
    private com.ccr.workflow.service.WarmFlowService warmFlowService;
    @Mock
    private com.ccr.vote.mapper.CcrApprovalActionTrailMapper approvalActionTrailMapper;
    @Mock
    private com.ccr.application.mapper.CcrApplicationMapper applicationMapper;
    @Mock
    private com.ccr.common.core.assignee.NodeAssigneeResolver nodeAssigneeResolver;

    @InjectMocks
    private VoteServiceImpl voteService;

    private CcrVoteRound round;
    private CcrVoteAssignment assignment;
    private CcrPricingItem pricingItem;

    @BeforeEach
    void setUp() {
        // 纯 Mockito 环境无 SqlSessionFactory,需手动初始化实体 TableInfo(Lambda 包装器列解析依赖)
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CcrVoteRound.class);
        TableInfoHelper.initTableInfo(assistant, CcrVoteRoundItem.class);
        TableInfoHelper.initTableInfo(assistant, CcrVoteAssignment.class);
        TableInfoHelper.initTableInfo(assistant, CcrBallot.class);
        TableInfoHelper.initTableInfo(assistant, CcrVoteResult.class);
        TableInfoHelper.initTableInfo(assistant, CcrPresidentDecision.class);
        TableInfoHelper.initTableInfo(assistant, CcrPricingItem.class);
        TableInfoHelper.initTableInfo(assistant, SysUserRead.class);

        round = new CcrVoteRound();
        round.setId(100L);
        round.setApplicationId(30L);
        round.setVoterCount(6);
        round.setRequiredCount(4);
        round.setStatus("VOTING");

        assignment = new CcrVoteAssignment();
        assignment.setId(900L);
        assignment.setRoundId(100L);
        assignment.setVoterUserId(2001L);
        assignment.setVoterAnonymNo("A");
        assignment.setStatus("PENDING");

        pricingItem = new CcrPricingItem();
        pricingItem.setId(10L);
        pricingItem.setApplicationId(30L);
        pricingItem.setPricingItemNo("PI001");
        pricingItem.setStatus(PricingItemStatus.VOTING.getCode());
        pricingItem.setCurrentApprovalRate(new BigDecimal("3.500000"));
        pricingItem.setVersionNo(1);

        // 缺省无节点指派配置(解析为空=不限制,保持角色兜底)
        org.mockito.Mockito.lenient().when(nodeAssigneeResolver.resolveUserIds(any(), any()))
                .thenReturn(List.of());
    }

    private SysUserRead committeeUser(Long id) {
        SysUserRead user = new SysUserRead();
        user.setId(id);
        user.setRoleCode(CurrentLoginUser.ROLE_COMMITTEE);
        user.setStatus("ENABLE");
        return user;
    }

    // ---------- 身份与批次校验 ----------

    @Test
    void submitBallot_reject_whenNotMember() {
        when(voteRoundMapper.selectById(100L)).thenReturn(round);
        when(roundItemMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(currentLoginUser.requireLoginId()).thenReturn(2999L);
        when(assignmentMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        ServiceException e = assertThrows(ServiceException.class,
                () -> voteService.submitBallot(100L, 10L, "APPROVE", null, null));
        assertEquals(ErrorCode.NODE_PERMISSION.getCode(), e.getCode());
        verify(ballotMapper, never()).insert(any(CcrBallot.class));
    }

    @Test
    void submitBallot_reject_whenItemNotInRound() {
        when(voteRoundMapper.selectById(100L)).thenReturn(round);
        when(roundItemMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        ServiceException e = assertThrows(ServiceException.class,
                () -> voteService.submitBallot(100L, 999L, "APPROVE", null, null));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
        verify(ballotMapper, never()).insert(any(CcrBallot.class));
    }

    @Test
    void submitBallot_reject_whenReplaced() {
        assignment.setStatus("REPLACED");
        when(voteRoundMapper.selectById(100L)).thenReturn(round);
        when(roundItemMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(currentLoginUser.requireLoginId()).thenReturn(2001L);
        when(assignmentMapper.selectOne(any(Wrapper.class))).thenReturn(assignment);

        ServiceException e = assertThrows(ServiceException.class,
                () -> voteService.submitBallot(100L, 10L, "APPROVE", null, null));
        assertEquals(ErrorCode.NODE_PERMISSION.getCode(), e.getCode());
        verify(ballotMapper, never()).insert(any(CcrBallot.class));
    }

    @Test
    void submitBallot_reject_whenDuplicateVote() {
        when(voteRoundMapper.selectById(100L)).thenReturn(round);
        when(roundItemMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(currentLoginUser.requireLoginId()).thenReturn(2001L);
        when(assignmentMapper.selectOne(any(Wrapper.class))).thenReturn(assignment);
        when(ballotMapper.insert(any(CcrBallot.class))).thenThrow(new DuplicateKeyException("uk_ballot"));

        ServiceException e = assertThrows(ServiceException.class,
                () -> voteService.submitBallot(100L, 10L, "APPROVE", null, null));
        assertEquals(ErrorCode.DUPLICATE_VOTE.getCode(), e.getCode());
    }

    @Test
    void submitBallot_reject_whenIdempotencyKeyRepeated() {
        when(voteRoundMapper.selectById(100L)).thenReturn(round);
        when(roundItemMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(currentLoginUser.requireLoginId()).thenReturn(2001L);
        when(assignmentMapper.selectOne(any(Wrapper.class))).thenReturn(assignment);
        when(ballotMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        ServiceException e = assertThrows(ServiceException.class,
                () -> voteService.submitBallot(100L, 10L, "APPROVE", null, "K-1"));
        assertEquals(ErrorCode.IDEMPOTENCY_REPEAT.getCode(), e.getCode());
        verify(ballotMapper, never()).insert(any(CcrBallot.class));
    }

    // ---------- 多分项批次:不提前关闭 ----------

    @Test
    void submitBallot_multiItemRound_notClosedEarly_andItemGoesPresidentDecision() {
        CcrVoteRoundItem ri1 = new CcrVoteRoundItem();
        ri1.setRoundId(100L);
        ri1.setPricingItemId(10L);
        CcrVoteRoundItem ri2 = new CcrVoteRoundItem();
        ri2.setRoundId(100L);
        ri2.setPricingItemId(11L);

        when(voteRoundMapper.selectById(100L)).thenReturn(round);
        when(roundItemMapper.selectCount(any(Wrapper.class))).thenReturn(1L, 2L);
        when(roundItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(ri1, ri2));
        when(currentLoginUser.requireLoginId()).thenReturn(2001L);
        when(assignmentMapper.selectOne(any(Wrapper.class))).thenReturn(assignment);
        // 依次:本人对分项10已投(1) → 本人对分项11未投(0,assignment 不置 SUBMITTED)
        //     → 分项10已收 6 票 → 其中赞成 5 票
        when(ballotMapper.selectCount(any(Wrapper.class))).thenReturn(1L, 0L, 6L, 5L);
        when(voteResultMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(pricingItemMapper.selectById(10L)).thenReturn(pricingItem);
        when(pricingItemMapper.updateById(any(CcrPricingItem.class))).thenReturn(1);
        // 批次共 2 个分项,仅 1 个出结果 → 不关闭
        CcrVoteResult counted = new CcrVoteResult();
        counted.setResult("PASS");
        when(voteResultMapper.selectList(any(Wrapper.class))).thenReturn(List.of(counted));

        voteService.submitBallot(100L, 10L, "APPROVE", null, null);

        // 计票通过 → PRESIDENT_DECISION(补齐该状态使用)
        verify(pricingItemMapper).updateById(argThat((CcrPricingItem i) ->
                PricingItemStatus.PRESIDENT_DECISION.getCode().equals(i.getStatus())));
        // 多分项批次:未全部出结果,批次不关闭
        verify(voteRoundMapper, never()).updateById(any(CcrVoteRound.class));
        // 委员未投完全部分项,assignment 不置 SUBMITTED
        verify(assignmentMapper, never()).updateById(any(CcrVoteAssignment.class));
    }

    @Test
    void submitBallot_lastItem_closesRound_andAssignmentSubmitted() {
        CcrVoteRoundItem ri1 = new CcrVoteRoundItem();
        ri1.setRoundId(100L);
        ri1.setPricingItemId(10L);

        when(voteRoundMapper.selectById(100L)).thenReturn(round);
        when(roundItemMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(roundItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(ri1));
        when(currentLoginUser.requireLoginId()).thenReturn(2001L);
        when(assignmentMapper.selectOne(any(Wrapper.class))).thenReturn(assignment);
        // 依次:本人对分项10已投(1,assignment 可置 SUBMITTED) → 分项10收齐 6 票 → 赞成 6 票
        when(ballotMapper.selectCount(any(Wrapper.class))).thenReturn(1L, 6L, 6L);
        when(voteResultMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(pricingItemMapper.selectById(10L)).thenReturn(pricingItem);
        when(pricingItemMapper.updateById(any(CcrPricingItem.class))).thenReturn(1);
        CcrVoteResult counted = new CcrVoteResult();
        counted.setResult("PASS");
        when(voteResultMapper.selectList(any(Wrapper.class))).thenReturn(List.of(counted));

        voteService.submitBallot(100L, 10L, "APPROVE", null, null);

        // 投完全部分项 → assignment SUBMITTED
        verify(assignmentMapper).updateById(argThat((CcrVoteAssignment a) -> "SUBMITTED".equals(a.getStatus())));
        // 全部分项出结果 → 批次关闭(任一通过 → PASSED)
        verify(voteRoundMapper).updateById(argThat((CcrVoteRound r) -> "PASSED".equals(r.getStatus())));
    }

    @Test
    void submitBallot_itemFail_goesRejected_andAggregates() {
        CcrVoteRoundItem ri1 = new CcrVoteRoundItem();
        ri1.setRoundId(100L);
        ri1.setPricingItemId(10L);

        when(voteRoundMapper.selectById(100L)).thenReturn(round);
        when(roundItemMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(roundItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(ri1));
        when(currentLoginUser.requireLoginId()).thenReturn(2001L);
        when(assignmentMapper.selectOne(any(Wrapper.class))).thenReturn(assignment);
        // 收齐 6 票,赞成仅 2 票 < 4 → FAIL
        when(ballotMapper.selectCount(any(Wrapper.class))).thenReturn(1L, 6L, 2L);
        when(voteResultMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(pricingItemMapper.selectById(10L)).thenReturn(pricingItem);
        when(pricingItemMapper.updateById(any(CcrPricingItem.class))).thenReturn(1);
        CcrVoteResult counted = new CcrVoteResult();
        counted.setResult("FAIL");
        when(voteResultMapper.selectList(any(Wrapper.class))).thenReturn(List.of(counted));

        voteService.submitBallot(100L, 10L, "REJECT", null, null);

        verify(pricingItemMapper).updateById(argThat((CcrPricingItem i) ->
                PricingItemStatus.REJECTED.getCode().equals(i.getStatus())));
        // 否决终态 → 主申请聚合
        verify(itemFinalizationService).afterItemTerminal(10L, null);
        verify(voteRoundMapper).updateById(argThat((CcrVoteRound r) -> "FAILED".equals(r.getStatus())));
    }

    // ---------- 行长决策 ----------

    @Test
    void presidentDecision_reject_whenNotPresident() {
        doThrow(new ServiceException(ErrorCode.FORBIDDEN.getCode(), "无权"))
                .when(currentLoginUser).requireAnyRole(CurrentLoginUser.ROLE_PRESIDENT);

        ServiceException e = assertThrows(ServiceException.class,
                () -> voteService.presidentDecision(10L, "APPROVE", null));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), e.getCode());
        verify(presidentDecisionMapper, never()).insert(any(CcrPresidentDecision.class));
    }

    @Test
    void presidentDecision_reject_whenItemNotPass() {
        pricingItem.setStatus(PricingItemStatus.VOTING.getCode());
        when(currentLoginUser.requireLoginId()).thenReturn(1003L);
        when(pricingItemMapper.selectById(10L)).thenReturn(pricingItem);

        ServiceException e = assertThrows(ServiceException.class,
                () -> voteService.presidentDecision(10L, "APPROVE", null));
        assertEquals(ErrorCode.FLOW_STATUS_CONFLICT.getCode(), e.getCode());
        verify(presidentDecisionMapper, never()).insert(any(CcrPresidentDecision.class));
    }

    @Test
    void presidentDecision_approve_goesFinal_andCreatesResolution() {
        pricingItem.setStatus(PricingItemStatus.PRESIDENT_DECISION.getCode());
        when(currentLoginUser.requireLoginId()).thenReturn(1003L);
        when(pricingItemMapper.selectById(10L)).thenReturn(pricingItem);
        when(pricingItemMapper.updateById(any(CcrPricingItem.class))).thenReturn(1);

        voteService.presidentDecision(10L, "APPROVE", null);

        verify(presidentDecisionMapper).insert(argThat((CcrPresidentDecision d) -> "APPROVE".equals(d.getDecision())
                && Long.valueOf(1003L).equals(d.getPresidentUserId())));
        verify(pricingItemMapper).updateById(argThat((CcrPricingItem i) ->
                PricingItemStatus.FINAL.getCode().equals(i.getStatus())
                        && i.getFinalRate() != null
                        && i.getFinalRate().compareTo(new BigDecimal("3.500000")) == 0));
        // 行长同意 → 决议生成(终态串联)
        verify(itemFinalizationService).afterItemTerminal(10L, "PRESIDENT_APPROVED");
    }

    @Test
    void presidentDecision_veto_goesVetoed() {
        pricingItem.setStatus(PricingItemStatus.COMMITTEE_PASS.getCode());
        when(currentLoginUser.requireLoginId()).thenReturn(1003L);
        when(pricingItemMapper.selectById(10L)).thenReturn(pricingItem);
        when(pricingItemMapper.updateById(any(CcrPricingItem.class))).thenReturn(1);

        voteService.presidentDecision(10L, "VETO", "风险不可控");

        verify(pricingItemMapper).updateById(argThat((CcrPricingItem i) ->
                PricingItemStatus.VETOED.getCode().equals(i.getStatus())
                        && "风险不可控".equals(i.getFinalReason())));
        verify(itemFinalizationService).afterItemTerminal(10L, null);
    }

    @Test
    void presidentDecision_duplicate_translatedToTaskProcessed() {
        pricingItem.setStatus(PricingItemStatus.PRESIDENT_DECISION.getCode());
        when(currentLoginUser.requireLoginId()).thenReturn(1003L);
        when(pricingItemMapper.selectById(10L)).thenReturn(pricingItem);
        when(presidentDecisionMapper.insert(any(CcrPresidentDecision.class)))
                .thenThrow(new DuplicateKeyException("uk_president_pricing"));

        ServiceException e = assertThrows(ServiceException.class,
                () -> voteService.presidentDecision(10L, "APPROVE", null));
        assertEquals(ErrorCode.TASK_PROCESSED.getCode(), e.getCode());
    }

    // ---------- 委员替补 ----------

    @Test
    void substitute_reject_whenOperatorNotAuthorized() {
        doThrow(new ServiceException(ErrorCode.FORBIDDEN.getCode(), "无权"))
                .when(currentLoginUser).requireAnyRole(CurrentLoginUser.ROLE_PRESIDENT, CurrentLoginUser.ROLE_ADMIN);

        ServiceException e = assertThrows(ServiceException.class,
                () -> voteService.substitute(100L, 2001L, 2002L, "请假"));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), e.getCode());
    }

    @Test
    void substitute_success_marksReplacedAndCreatesAssignment() {
        when(voteRoundMapper.selectById(100L)).thenReturn(round);
        when(assignmentMapper.selectOne(any(Wrapper.class))).thenReturn(assignment);
        SysUserRead toUser = committeeUser(2002L);
        when(sysUserReadMapper.selectById(2002L)).thenReturn(toUser);
        when(assignmentMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        CcrVoteAssignment created = voteService.substitute(100L, 2001L, 2002L, "请假");

        // 原 assignment 置 REPLACED
        verify(assignmentMapper).updateById(argThat((CcrVoteAssignment a) -> "REPLACED".equals(a.getStatus())));
        // 新 assignment 记录 substitute_from_user_id/substitute_reason,沿用原匿名席位
        verify(assignmentMapper).insert(argThat((CcrVoteAssignment a) ->
                Long.valueOf(2002L).equals(a.getVoterUserId())
                        && Long.valueOf(2001L).equals(a.getSubstituteFromUserId())
                        && "请假".equals(a.getSubstituteReason())
                        && "A".equals(a.getVoterAnonymNo())));
        assertEquals(Long.valueOf(2001L), created.getSubstituteFromUserId());
    }

    @Test
    void substitute_reject_whenSubstituteNotCommittee() {
        when(voteRoundMapper.selectById(100L)).thenReturn(round);
        when(assignmentMapper.selectOne(any(Wrapper.class))).thenReturn(assignment);
        SysUserRead toUser = committeeUser(2002L);
        toUser.setRoleCode(CurrentLoginUser.ROLE_BRANCH_MANAGER);
        when(sysUserReadMapper.selectById(2002L)).thenReturn(toUser);

        ServiceException e = assertThrows(ServiceException.class,
                () -> voteService.substitute(100L, 2001L, 2002L, "请假"));
        assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
        verify(assignmentMapper, never()).insert(any(CcrVoteAssignment.class));
    }

    // ---------- 自动合批 ----------

    @Test
    void createGroupRound_noPending_returnsNull() {
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertNull(voteService.createGroupRound(30L));
        verify(voteRoundMapper, never()).insert(any(CcrVoteRound.class));
    }

    @Test
    void createGroupRound_reject_whenMembersInsufficient() {
        pricingItem.setStatus(PricingItemStatus.ROUTING.getCode());
        pricingItem.setCurrentNodeCode("SIX_PEOPLE_GROUP");
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(pricingItem));
        when(voteRoundMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(sysUserReadMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(committeeUser(2001L), committeeUser(2002L)));

        ServiceException e = assertThrows(ServiceException.class,
                () -> voteService.createGroupRound(30L));
        assertEquals(ErrorCode.INTERNAL_ERROR.getCode(), e.getCode());
        verify(voteRoundMapper, never()).insert(any(CcrVoteRound.class));
    }

    @Test
    void createGroupRound_success_freezesSixMembers_andItemsVoting() {
        pricingItem.setStatus(PricingItemStatus.ROUTING.getCode());
        pricingItem.setCurrentNodeCode("SIX_PEOPLE_GROUP");
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(pricingItem));
        when(voteRoundMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(sysUserReadMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                committeeUser(2001L), committeeUser(2002L), committeeUser(2003L),
                committeeUser(2004L), committeeUser(2005L), committeeUser(2006L)));
        when(pricingItemMapper.selectById(10L)).thenReturn(pricingItem);
        when(pricingItemMapper.updateById(any(CcrPricingItem.class))).thenReturn(1);

        CcrVoteRound created = voteService.createGroupRound(30L);

        verify(voteRoundMapper).insert(any(CcrVoteRound.class));
        verify(roundItemMapper, times(1)).insert(any(CcrVoteRoundItem.class));
        verify(assignmentMapper, times(6)).insert(any(CcrVoteAssignment.class));
        // 入批分项置 VOTING
        verify(pricingItemMapper).updateById(argThat((CcrPricingItem i) ->
                PricingItemStatus.VOTING.getCode().equals(i.getStatus())));
        assertEquals(Integer.valueOf(6), created.getVoterCount());
    }

    // ---------- 节点审批人配置(§5.5.1) ----------

    @Test
    void createGroupRound_assigneeConfigured_freezesResolvedMembers() {
        // SIX_PEOPLE_GROUP 节点配置了有效指派 → 按解析结果冻结名单,不走角色兜底
        pricingItem.setStatus(PricingItemStatus.ROUTING.getCode());
        pricingItem.setCurrentNodeCode("SIX_PEOPLE_GROUP");
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(pricingItem));
        when(voteRoundMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        List<Long> assigneeIds = List.of(2001L, 2002L, 2003L, 2004L, 2005L, 2006L);
        when(nodeAssigneeResolver.resolveUserIds(org.mockito.ArgumentMatchers.eq("SIX_PEOPLE_GROUP"), any()))
                .thenReturn(assigneeIds);
        when(sysUserReadMapper.selectBatchIds(assigneeIds)).thenReturn(List.of(
                committeeUser(2001L), committeeUser(2002L), committeeUser(2003L),
                committeeUser(2004L), committeeUser(2005L), committeeUser(2006L)));
        when(pricingItemMapper.selectById(10L)).thenReturn(pricingItem);
        when(pricingItemMapper.updateById(any(CcrPricingItem.class))).thenReturn(1);

        CcrVoteRound created = voteService.createGroupRound(30L);

        verify(voteRoundMapper).insert(any(CcrVoteRound.class));
        verify(assignmentMapper, times(6)).insert(any(CcrVoteAssignment.class));
        assertEquals(Integer.valueOf(6), created.getVoterCount());
    }

    @Test
    void createGroupRound_reject_whenAssigneesInsufficient() {
        // 节点指派解析出启用用户不足 6 人 → 配置错误
        pricingItem.setStatus(PricingItemStatus.ROUTING.getCode());
        pricingItem.setCurrentNodeCode("SIX_PEOPLE_GROUP");
        when(pricingItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(pricingItem));
        when(voteRoundMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        List<Long> assigneeIds = List.of(2001L, 2002L);
        when(nodeAssigneeResolver.resolveUserIds(org.mockito.ArgumentMatchers.eq("SIX_PEOPLE_GROUP"), any()))
                .thenReturn(assigneeIds);
        when(sysUserReadMapper.selectBatchIds(assigneeIds))
                .thenReturn(List.of(committeeUser(2001L), committeeUser(2002L)));

        ServiceException e = assertThrows(ServiceException.class,
                () -> voteService.createGroupRound(30L));
        assertEquals(ErrorCode.INTERNAL_ERROR.getCode(), e.getCode());
        verify(voteRoundMapper, never()).insert(any(CcrVoteRound.class));
    }

    @Test
    void presidentDecision_reject_whenNotInNodeAssignees() {
        // PRESIDENT 节点配置了指定决策人,当前登录人不在指派范围 → 拒绝
        pricingItem.setStatus(PricingItemStatus.PRESIDENT_DECISION.getCode());
        when(currentLoginUser.requireLoginId()).thenReturn(3001L);
        when(pricingItemMapper.selectById(10L)).thenReturn(pricingItem);
        when(nodeAssigneeResolver.resolveUserIds(org.mockito.ArgumentMatchers.eq("PRESIDENT"), any()))
                .thenReturn(List.of(3999L));

        ServiceException e = assertThrows(ServiceException.class,
                () -> voteService.presidentDecision(10L, "APPROVE", null));
        assertEquals(ErrorCode.NODE_PERMISSION.getCode(), e.getCode());
        verify(presidentDecisionMapper, never()).insert(any(CcrPresidentDecision.class));
    }

    // ---------- 超时强制计票(§7.5.5) ----------

    @Test
    void scanTimeoutRounds_partialPass_goesPresidentDecision_andTrails() {
        round.setRoundStartTime(LocalDateTime.now().minusHours(100));
        CcrVoteRoundItem ri1 = new CcrVoteRoundItem();
        ri1.setRoundId(100L);
        ri1.setPricingItemId(10L);
        when(voteRoundMapper.selectList(any(Wrapper.class))).thenReturn(List.of(round));
        when(roundItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(ri1));
        when(roundItemMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(voteResultMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        // 超时强制计票:已投 5 票,赞成 4 票 ≥ 4 → 通过
        when(ballotMapper.selectCount(any(Wrapper.class))).thenReturn(5L, 4L);
        when(pricingItemMapper.selectById(10L)).thenReturn(pricingItem);
        when(pricingItemMapper.updateById(any(CcrPricingItem.class))).thenReturn(1);
        CcrVoteResult counted = new CcrVoteResult();
        counted.setResult("PASS");
        when(voteResultMapper.selectList(any(Wrapper.class))).thenReturn(List.of(counted));

        int countedItems = voteService.scanTimeoutRounds();

        assertEquals(1, countedItems);
        // 按已投票数计票(5 票而非 6 票)
        verify(voteResultMapper).insert(argThat((CcrVoteResult r) ->
                Integer.valueOf(5).equals(r.getSubmittedCount())
                        && Integer.valueOf(4).equals(r.getApproveCount())
                        && "PASS".equals(r.getResult())));
        verify(pricingItemMapper).updateById(argThat((CcrPricingItem i) ->
                PricingItemStatus.PRESIDENT_DECISION.getCode().equals(i.getStatus())));
        // §14.7 计票留痕:from VOTING → to PRESIDENT_DECISION
        verify(approvalActionTrailMapper).insert(argThat((CcrApprovalActionTrail t) ->
                "COUNT_PASS".equals(t.getActionType())
                        && PricingItemStatus.VOTING.getCode().equals(t.getFromStatus())
                        && PricingItemStatus.PRESIDENT_DECISION.getCode().equals(t.getToStatus())));
        // 批次关闭(PASSED)
        verify(voteRoundMapper).updateById(argThat((CcrVoteRound r) -> "PASSED".equals(r.getStatus())));
    }

    @Test
    void scanTimeoutRounds_partialFail_goesRejected_andAggregates() {
        round.setRoundStartTime(LocalDateTime.now().minusHours(100));
        CcrVoteRoundItem ri1 = new CcrVoteRoundItem();
        ri1.setRoundId(100L);
        ri1.setPricingItemId(10L);
        when(voteRoundMapper.selectList(any(Wrapper.class))).thenReturn(List.of(round));
        when(roundItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(ri1));
        when(roundItemMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(voteResultMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        // 已投 3 票,赞成 2 票 < 4 → 不通过
        when(ballotMapper.selectCount(any(Wrapper.class))).thenReturn(3L, 2L);
        when(pricingItemMapper.selectById(10L)).thenReturn(pricingItem);
        when(pricingItemMapper.updateById(any(CcrPricingItem.class))).thenReturn(1);
        CcrVoteResult counted = new CcrVoteResult();
        counted.setResult("FAIL");
        when(voteResultMapper.selectList(any(Wrapper.class))).thenReturn(List.of(counted));

        voteService.scanTimeoutRounds();

        verify(pricingItemMapper).updateById(argThat((CcrPricingItem i) ->
                PricingItemStatus.REJECTED.getCode().equals(i.getStatus())));
        verify(itemFinalizationService).afterItemTerminal(10L, null);
        verify(approvalActionTrailMapper).insert(argThat((CcrApprovalActionTrail t) ->
                "COUNT_REJECT".equals(t.getActionType())
                        && PricingItemStatus.REJECTED.getCode().equals(t.getToStatus())));
        verify(voteRoundMapper).updateById(argThat((CcrVoteRound r) -> "FAILED".equals(r.getStatus())));
    }

    @Test
    void scanTimeoutRounds_notExpired_skips() {
        round.setRoundStartTime(LocalDateTime.now().minusHours(1));
        // 批次未超时 → 查询结果为空
        when(voteRoundMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertEquals(0, voteService.scanTimeoutRounds());
        verify(voteResultMapper, never()).insert(any(CcrVoteResult.class));
    }

    // ---------- 行长匿名意见(§12.7) ----------

    @Test
    void listRoundOpinions_mapsAnonymNo_withoutRealIdentity() {
        when(voteRoundMapper.selectById(100L)).thenReturn(round);
        when(assignmentMapper.selectList(any(Wrapper.class))).thenReturn(List.of(assignment));
        CcrBallot ballot = new CcrBallot();
        ballot.setRoundId(100L);
        ballot.setPricingItemId(10L);
        ballot.setVoterUserHash(DigestUtil.sha256Hex("2001"));
        ballot.setVoteChoice("APPROVE");
        ballot.setVoteComment("同意,风险可控");
        ballot.setSubmitTime(LocalDateTime.now());
        when(ballotMapper.selectList(any(Wrapper.class))).thenReturn(List.of(ballot));

        List<Map<String, Object>> result = voteService.listRoundOpinions(100L);

        assertEquals(1, result.size());
        assertEquals(10L, result.get(0).get("pricingItemId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> opinions = (List<Map<String, Object>>) result.get(0).get("opinions");
        assertEquals(1, opinions.size());
        // 匿名码映射自批次名单,不携带真实身份
        assertEquals("A", opinions.get(0).get("anonymNo"));
        assertEquals("APPROVE", opinions.get(0).get("voteChoice"));
        assertEquals("同意,风险可控", opinions.get(0).get("voteComment"));
        assertFalse(opinions.get(0).containsKey("voterUserId"));
    }

    @Test
    void listRoundOpinions_reject_whenRoundMissing() {
        when(voteRoundMapper.selectById(100L)).thenReturn(null);

        ServiceException e = assertThrows(ServiceException.class,
                () -> voteService.listRoundOpinions(100L));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), e.getCode());
    }

    // ---------- 行长决策留痕(§14.7) ----------

    @Test
    void presidentDecision_approve_writesTrailFromToStatus() {
        pricingItem.setStatus(PricingItemStatus.PRESIDENT_DECISION.getCode());
        when(currentLoginUser.requireLoginId()).thenReturn(1003L);
        when(pricingItemMapper.selectById(10L)).thenReturn(pricingItem);
        when(pricingItemMapper.updateById(any(CcrPricingItem.class))).thenReturn(1);

        voteService.presidentDecision(10L, "APPROVE", "同意执行");

        verify(approvalActionTrailMapper).insert(argThat((CcrApprovalActionTrail t) ->
                "PRESIDENT_APPROVE".equals(t.getActionType())
                        && PricingItemStatus.PRESIDENT_DECISION.getCode().equals(t.getFromStatus())
                        && PricingItemStatus.FINAL.getCode().equals(t.getToStatus())
                        && Long.valueOf(1003L).equals(t.getOperatorId())));
    }
}
