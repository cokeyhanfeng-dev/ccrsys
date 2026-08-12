package com.ccr.vote.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.domain.CcrPricingItem;
import com.ccr.application.enums.PricingItemStatus;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.application.mapper.CcrPricingItemMapper;
import com.ccr.application.support.FrozenRoutePlan;
import com.ccr.common.core.assignee.NodeAssigneeResolver;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.vote.domain.CcrApprovalActionTrail;
import com.ccr.vote.domain.CcrBallot;
import com.ccr.vote.domain.CcrPresidentDecision;
import com.ccr.vote.domain.CcrVoteAssignment;
import com.ccr.vote.domain.CcrVoteResult;
import com.ccr.vote.domain.CcrVoteRound;
import com.ccr.vote.domain.CcrVoteRoundItem;
import com.ccr.vote.mapper.CcrApprovalActionTrailMapper;
import com.ccr.vote.mapper.CcrBallotMapper;
import com.ccr.vote.mapper.CcrPresidentDecisionMapper;
import com.ccr.vote.mapper.CcrVoteAssignmentMapper;
import com.ccr.vote.mapper.CcrVoteResultMapper;
import com.ccr.vote.mapper.CcrVoteRoundItemMapper;
import com.ccr.vote.mapper.CcrVoteRoundMapper;
import com.ccr.vote.read.SysUserRead;
import com.ccr.vote.mapper.SysUserReadMapper;
import com.ccr.vote.service.ItemFinalizationService;
import com.ccr.vote.service.VoteService;
import com.ccr.vote.support.CurrentLoginUser;
import com.ccr.workflow.service.WarmFlowService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 六人表决与行长决策实现
 * 安全口径:投票人/行长/替补发起人均取 Sa-Token 登录人;批次名单由系统角色冻结,不接受请求传入。
 * 批次关闭口径:计票按分项粒度,批次内全部分项均有计票结果后才关闭批次
 * (PASSED=任一分项通过/FAILED=全部未过,分项粒度结论见 ccr_vote_result)。
 * 开发期说明:ccr_ballot 人员信息以"明文+查询哈希"落库,接入行内加密组件后改为密文(§9.3)
 */
@Slf4j
@Service
public class VoteServiceImpl implements VoteService {

    private static final int DEFAULT_VOTER_COUNT = 6;
    private static final int DEFAULT_REQUIRED_COUNT = 4;

    /** 进行中批次状态(同一时间一个分项只允许处于一个进行中批次,服务层保证) */
    private static final List<String> IN_PROGRESS_ROUND_STATUS = List.of("CREATED", "VOTING", "COUNTING");

    @Resource
    private CcrVoteRoundMapper voteRoundMapper;
    @Resource
    private CcrVoteRoundItemMapper roundItemMapper;
    @Resource
    private CcrVoteAssignmentMapper assignmentMapper;
    @Resource
    private CcrBallotMapper ballotMapper;
    @Resource
    private CcrVoteResultMapper voteResultMapper;
    @Resource
    private CcrPresidentDecisionMapper presidentDecisionMapper;
    @Resource
    private CcrPricingItemMapper pricingItemMapper;
    @Resource
    private SysUserReadMapper sysUserReadMapper;
    @Resource
    private CurrentLoginUser currentLoginUser;
    @Resource
    private ItemFinalizationService itemFinalizationService;
    @Resource
    private WarmFlowService warmFlowService;
    @Resource
    private CcrApprovalActionTrailMapper approvalActionTrailMapper;
    @Resource
    private CcrApplicationMapper applicationMapper;
    @Resource
    private NodeAssigneeResolver nodeAssigneeResolver;

    /** 表决批次超时时长(§7.5.5,默认 72h,配置 ccr.vote.round-timeout-hours) */
    @Value("${ccr.vote.round-timeout-hours:72}")
    private long roundTimeoutHours = 72;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CcrVoteRound createGroupRound(Long applicationId) {
        if (applicationId == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "申请必填");
        }
        // 同申请、当前处于小组节点且未入任何进行中批次(状态仍为 ROUTING)的分项合为一批
        List<CcrPricingItem> pending = pricingItemMapper.selectList(new LambdaQueryWrapper<CcrPricingItem>()
                .eq(CcrPricingItem::getApplicationId, applicationId)
                .eq(CcrPricingItem::getCurrentNodeCode, "SIX_PEOPLE_GROUP")
                .eq(CcrPricingItem::getStatus, PricingItemStatus.ROUTING.getCode())
                .orderByAsc(CcrPricingItem::getCreateTime));
        if (pending.isEmpty()) {
            return null;
        }
        // 双保险:剔除已挂在进行中批次下的分项(正常状态机不会命中,防脏数据重复入批)
        List<CcrVoteRound> inProgress = voteRoundMapper.selectList(new LambdaQueryWrapper<CcrVoteRound>()
                .eq(CcrVoteRound::getApplicationId, applicationId)
                .in(CcrVoteRound::getStatus, IN_PROGRESS_ROUND_STATUS));
        if (!inProgress.isEmpty()) {
            Set<Long> busyItemIds = roundItemMapper.selectList(new LambdaQueryWrapper<CcrVoteRoundItem>()
                            .in(CcrVoteRoundItem::getRoundId,
                                    inProgress.stream().map(CcrVoteRound::getId).collect(Collectors.toList())))
                    .stream().map(CcrVoteRoundItem::getPricingItemId).collect(Collectors.toSet());
            pending = pending.stream().filter(i -> !busyItemIds.contains(i.getId())).collect(Collectors.toList());
        }
        if (pending.isEmpty()) {
            return null;
        }
        // 冻结 6 人名单(§5.5.1):节点配置了有效指派时按解析结果取 6 人;未配置按角色兜底
        List<SysUserRead> members = resolveRoundMembers(applicationId);
        return doCreateRound(applicationId,
                pending.stream().map(CcrPricingItem::getId).collect(Collectors.toList()), members);
    }

    /**
     * 表决委员名单:SIX_PEOPLE_GROUP 节点配置了有效指派时,按解析结果(启用用户,取前 6 人,
     * 不足 6 人抛配置错误)冻结;未配置保持原角色兜底(启用小组成员取 6 人)
     */
    private List<SysUserRead> resolveRoundMembers(Long applicationId) {
        List<Long> assigneeIds = nodeAssigneeResolver.resolveUserIds("SIX_PEOPLE_GROUP",
                applicantOrgId(applicationId));
        if (!assigneeIds.isEmpty()) {
            Map<Long, SysUserRead> byId = new LinkedHashMap<>();
            for (SysUserRead user : sysUserReadMapper.selectBatchIds(assigneeIds)) {
                byId.put(user.getId(), user);
            }
            List<SysUserRead> members = assigneeIds.stream().map(byId::get).filter(Objects::nonNull)
                    .filter(u -> "ENABLE".equals(u.getStatus()))
                    .limit(DEFAULT_VOTER_COUNT).collect(Collectors.toList());
            if (members.size() < DEFAULT_VOTER_COUNT) {
                throw new ServiceException(ErrorCode.INTERNAL_ERROR.getCode(),
                        "表决委员指派不足:节点指派解析出启用用户 " + members.size()
                                + " 人,需 " + DEFAULT_VOTER_COUNT + " 人");
            }
            return members;
        }
        // 角色兜底:系统角色为小组成员的启用用户取 6 人,不足 6 人抛配置错误
        List<SysUserRead> members = sysUserReadMapper.selectList(new LambdaQueryWrapper<SysUserRead>()
                .eq(SysUserRead::getRoleCode, CurrentLoginUser.ROLE_COMMITTEE)
                .eq(SysUserRead::getStatus, "ENABLE")
                .orderByAsc(SysUserRead::getId)
                .last("limit " + DEFAULT_VOTER_COUNT));
        if (members.size() < DEFAULT_VOTER_COUNT) {
            throw new ServiceException(ErrorCode.INTERNAL_ERROR.getCode(),
                    "表决委员配置不足:小组成员启用用户 " + members.size() + " 人,需 " + DEFAULT_VOTER_COUNT + " 人");
        }
        return members;
    }

    /** 申请人机构(ccr_application.applicant_org_id);申请不存在返回 null(DEPT 层不命中) */
    private Long applicantOrgId(Long applicationId) {
        CcrApplication application = applicationMapper.selectById(applicationId);
        return application == null ? null : application.getApplicantOrgId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitBallot(Long roundId, Long pricingItemId, String choice, String comment, String idempotencyKey) {
        if (!"APPROVE".equals(choice) && !"REJECT".equals(choice)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "票型只能为 APPROVE/REJECT");
        }
        CcrVoteRound round = voteRoundMapper.selectById(roundId);
        if (round == null || !"VOTING".equals(round.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(), "批次不在表决中");
        }
        // (roundId, pricingItemId) 必须属于该批次
        Long inRound = roundItemMapper.selectCount(new LambdaQueryWrapper<CcrVoteRoundItem>()
                .eq(CcrVoteRoundItem::getRoundId, roundId)
                .eq(CcrVoteRoundItem::getPricingItemId, pricingItemId));
        if (inRound == null || inRound == 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "分项不属于该表决批次");
        }
        // 委员只能投本人任务(身份取登录人,不接受传参)
        Long voterUserId = currentLoginUser.requireLoginId();
        CcrVoteAssignment assignment = assignmentMapper.selectOne(
                new LambdaQueryWrapper<CcrVoteAssignment>()
                        .eq(CcrVoteAssignment::getRoundId, roundId)
                        .eq(CcrVoteAssignment::getVoterUserId, voterUserId));
        if (assignment == null) {
            throw new ServiceException(ErrorCode.NODE_PERMISSION.getCode(), "该用户不是本批次委员");
        }
        if ("REPLACED".equals(assignment.getStatus())) {
            throw new ServiceException(ErrorCode.NODE_PERMISSION.getCode(), "已被替补,不再具有本批次投票权");
        }
        // 幂等键防护(uk_ballot_idem 兜底)
        if (StrUtil.isNotBlank(idempotencyKey)) {
            Long dupKey = ballotMapper.selectCount(new LambdaQueryWrapper<CcrBallot>()
                    .eq(CcrBallot::getIdempotencyKey, idempotencyKey));
            if (dupKey != null && dupKey > 0) {
                throw new ServiceException(ErrorCode.IDEMPOTENCY_REPEAT.getCode(), "重复提交:幂等键已处理");
            }
        }

        CcrBallot ballot = new CcrBallot();
        ballot.setRoundId(roundId);
        ballot.setPricingItemId(pricingItemId);
        // 开发期明文+哈希;接入加密组件后替换为密文
        ballot.setVoterUserIdCipher(String.valueOf(voterUserId));
        ballot.setVoterUserHash(voterHash(voterUserId));
        ballot.setVoteChoice(choice);
        ballot.setVoteComment(comment);
        ballot.setSubmitTime(LocalDateTime.now());
        ballot.setIdempotencyKey(idempotencyKey);
        try {
            ballotMapper.insert(ballot);
        } catch (DuplicateKeyException e) {
            throw new ServiceException(ErrorCode.DUPLICATE_VOTE.getCode(), "重复投票:每人每分项只能投一次");
        }

        // 单委员 assignment 仅在投完批次内全部分项后置 SUBMITTED
        markAssignmentSubmittedIfDone(round, assignment);
        // Warm-Flow 业务轨迹:投票提交(操作人传登录人id字符串;失败仅记日志,不阻断主流程)
        CcrPricingItem ballotItem = pricingItemMapper.selectById(pricingItemId);
        warmFlowService.recordBusinessTrail(
                ballotItem == null ? String.valueOf(pricingItemId) : ballotItem.getPricingItemNo(),
                "SIX_PEOPLE_GROUP", choice, String.valueOf(voterUserId), comment);
        // 分项粒度计票;批次内全部分项出结果后才关闭批次
        countItemIfReady(round, pricingItemId);
    }

    @Override
    public Map<String, Object> myBallot(Long roundId, Long pricingItemId) {
        Long voterUserId = currentLoginUser.requireLoginId();
        CcrBallot ballot = ballotMapper.selectOne(new LambdaQueryWrapper<CcrBallot>()
                .eq(CcrBallot::getRoundId, roundId)
                .eq(CcrBallot::getPricingItemId, pricingItemId)
                .eq(CcrBallot::getVoterUserHash, voterHash(voterUserId)));
        if (ballot == null) {
            return null;
        }
        // 只返回本人票型,不携带任何他人信息
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("voteChoice", ballot.getVoteChoice());
        result.put("voteComment", ballot.getVoteComment());
        result.put("submitTime", ballot.getSubmitTime());
        return result;
    }

    @Override
    public CcrVoteResult getVoteResult(Long pricingItemId) {
        return voteResultMapper.selectOne(
                new LambdaQueryWrapper<CcrVoteResult>()
                        .eq(CcrVoteResult::getPricingItemId, pricingItemId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void presidentDecision(Long pricingItemId, String decision, String opinion) {
        if (!"APPROVE".equals(decision) && !"VETO".equals(decision)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "决策只能为 APPROVE/VETO");
        }
        if ("VETO".equals(decision) && StrUtil.isBlank(opinion)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "一票否决必须填写意见(§7.5)");
        }
        // 行长决策校验 PRESIDENT 角色(身份取登录人)
        currentLoginUser.requireAnyRole(CurrentLoginUser.ROLE_PRESIDENT);
        Long presidentUserId = currentLoginUser.requireLoginId();

        CcrPricingItem item = pricingItemMapper.selectById(pricingItemId);
        if (item == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "定价分项不存在");
        }
        // 只接收表决通过分项:计票通过置 PRESIDENT_DECISION,兼容历史 COMMITTEE_PASS
        if (!PricingItemStatus.COMMITTEE_PASS.getCode().equals(item.getStatus())
                && !PricingItemStatus.PRESIDENT_DECISION.getCode().equals(item.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(), "只接收六人表决通过的分项(§7.5)");
        }
        if (FrozenRoutePlan.hasFrozenPlan(item) && !FrozenRoutePlan.requiresPresident(item)) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "该分项冻结路由无需行长决策");
        }
        // 节点审批人配置限制(§5.5.1):PRESIDENT 节点配置有效指派时,仅解析出的处理人可决策
        List<Long> presidentAssignees = nodeAssigneeResolver.resolveUserIds("PRESIDENT",
                applicantOrgId(item.getApplicationId()));
        if (!presidentAssignees.isEmpty() && !presidentAssignees.contains(presidentUserId)) {
            throw new ServiceException(ErrorCode.NODE_PERMISSION.getCode(),
                    "PRESIDENT节点已配置指定决策人,当前登录人不在指派范围内");
        }

        CcrPresidentDecision pd = new CcrPresidentDecision();
        pd.setPricingItemId(pricingItemId);
        pd.setDecision(decision);
        pd.setOpinion(opinion);
        pd.setPresidentUserId(presidentUserId);
        pd.setDecisionTime(LocalDateTime.now());
        pd.setBusinessVersion(1);
        try {
            presidentDecisionMapper.insert(pd);
        } catch (DuplicateKeyException e) {
            // uk_president_pricing 唯一约束兜底重复决策
            throw new ServiceException(ErrorCode.TASK_PROCESSED.getCode(), "该分项已完成行长决策");
        }

        if ("APPROVE".equals(decision)) {
            // 行长同意→FINAL 执行,回填最终利率
            String fromStatus = item.getStatus();
            item.setStatus(PricingItemStatus.FINAL.getCode());
            if (item.getFinalRate() == null) {
                item.setFinalRate(item.getCurrentApprovalRate());
            }
            updateItemWithLock(item);
            insertTrail(pricingItemId, "PRESIDENT_APPROVE", "PRESIDENT", presidentUserId, opinion,
                    fromStatus, PricingItemStatus.FINAL.getCode());
            itemFinalizationService.afterItemTerminal(pricingItemId, "PRESIDENT_APPROVED");
        } else {
            String fromStatus = item.getStatus();
            item.setStatus(PricingItemStatus.VETOED.getCode());
            item.setFinalReason(opinion);
            updateItemWithLock(item);
            insertTrail(pricingItemId, "VETO", "PRESIDENT", presidentUserId, opinion,
                    fromStatus, PricingItemStatus.VETOED.getCode());
            itemFinalizationService.afterItemTerminal(pricingItemId, null);
        }
        // Warm-Flow 业务轨迹:行长决策(操作人传登录人id字符串;失败仅记日志,不阻断主流程)
        warmFlowService.recordBusinessTrail(item.getPricingItemNo(), "PRESIDENT", decision,
                String.valueOf(presidentUserId), opinion);
        log.info("分项 {} 行长决策 {} 完成", pricingItemId, decision);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CcrVoteAssignment substitute(Long roundId, Long fromUserId, Long toUserId, String reason) {
        // 授权角色:行长或流程管理员(admin)
        currentLoginUser.requireAnyRole(CurrentLoginUser.ROLE_PRESIDENT, CurrentLoginUser.ROLE_ADMIN);
        if (fromUserId == null || toUserId == null || StrUtil.isBlank(reason)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "原委员/替补委员/替补原因必填");
        }
        if (fromUserId.equals(toUserId)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "替补委员不能与原委员相同");
        }
        CcrVoteRound round = voteRoundMapper.selectById(roundId);
        if (round == null || !"VOTING".equals(round.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(), "批次不在表决中,不能替补");
        }
        CcrVoteAssignment fromAssignment = assignmentMapper.selectOne(
                new LambdaQueryWrapper<CcrVoteAssignment>()
                        .eq(CcrVoteAssignment::getRoundId, roundId)
                        .eq(CcrVoteAssignment::getVoterUserId, fromUserId));
        if (fromAssignment == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "原委员不在本批次");
        }
        if ("REPLACED".equals(fromAssignment.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(), "原委员已被替补");
        }
        if ("SUBMITTED".equals(fromAssignment.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(), "原委员已完成全部投票,无需替补");
        }
        SysUserRead toUser = sysUserReadMapper.selectById(toUserId);
        if (toUser == null || !"ENABLE".equals(toUser.getStatus())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "替补委员用户不存在或已停用");
        }
        if (!CurrentLoginUser.ROLE_COMMITTEE.equals(toUser.getRoleCode())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "替补委员须为小组成员");
        }
        Long dup = assignmentMapper.selectCount(new LambdaQueryWrapper<CcrVoteAssignment>()
                .eq(CcrVoteAssignment::getRoundId, roundId)
                .eq(CcrVoteAssignment::getVoterUserId, toUserId));
        if (dup != null && dup > 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "替补委员已是本批次委员");
        }

        fromAssignment.setStatus("REPLACED");
        assignmentMapper.updateById(fromAssignment);

        // 口径(§7.4):原成员已投票据保留有效;替补委员沿用原席位匿名编号,只投原成员未投的剩余分项
        CcrVoteAssignment substitute = new CcrVoteAssignment();
        substitute.setRoundId(roundId);
        substitute.setVoterUserId(toUserId);
        substitute.setVoterAnonymNo(fromAssignment.getVoterAnonymNo());
        substitute.setSubstituteFromUserId(fromUserId);
        substitute.setSubstituteReason(reason);
        substitute.setStatus("PENDING");
        assignmentMapper.insert(substitute);
        log.info("批次 {} 委员 {} 被 {} 替补,原因: {}", roundId, fromUserId, toUserId, reason);
        return substitute;
    }

    // ---------- 超时计票与匿名意见 ----------

    /**
     * 表决超时强制计票(§7.5.5):VOTING 批次超过 ccr.vote.round-timeout-hours(默认 72h)
     * 按已投票数计票——赞成≥requiredCount 通过,否则不通过;结果落库与正常计票一致
     * (含 PRESIDENT_DECISION 流转与批次关闭)
     *
     * @return 本次强制计票的分项数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int scanTimeoutRounds() {
        LocalDateTime deadline = LocalDateTime.now().minusHours(roundTimeoutHours);
        List<CcrVoteRound> expired = voteRoundMapper.selectList(new LambdaQueryWrapper<CcrVoteRound>()
                .eq(CcrVoteRound::getStatus, "VOTING")
                .lt(CcrVoteRound::getRoundStartTime, deadline));
        int count = 0;
        for (CcrVoteRound round : expired) {
            List<CcrVoteRoundItem> items = roundItemMapper.selectList(
                    new LambdaQueryWrapper<CcrVoteRoundItem>()
                            .eq(CcrVoteRoundItem::getRoundId, round.getId()));
            for (CcrVoteRoundItem item : items) {
                Long existed = voteResultMapper.selectCount(new LambdaQueryWrapper<CcrVoteResult>()
                        .eq(CcrVoteResult::getPricingItemId, item.getPricingItemId()));
                if (existed != null && existed > 0) {
                    continue;
                }
                countItem(round, item.getPricingItemId(), true);
                count++;
            }
        }
        if (count > 0) {
            log.info("表决超时扫描完成,强制计票分项 {} 个", count);
        }
        return count;
    }

    /**
     * 行长查看批次委员匿名意见(§12.7):按分项返回委员匿名码(A-F)+票型+意见,不含真实身份。
     * 匿名码经票据哈希与批次 assignment 名单映射(替补沿用原席位匿名码),不反解用户。
     */
    @Override
    public List<Map<String, Object>> listRoundOpinions(Long roundId) {
        CcrVoteRound round = voteRoundMapper.selectById(roundId);
        if (round == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "表决批次不存在");
        }
        List<CcrVoteAssignment> assignments = assignmentMapper.selectList(
                new LambdaQueryWrapper<CcrVoteAssignment>()
                        .eq(CcrVoteAssignment::getRoundId, roundId));
        Map<String, String> hashToAnonym = assignments.stream().collect(Collectors.toMap(
                a -> voterHash(a.getVoterUserId()), CcrVoteAssignment::getVoterAnonymNo, (x, y) -> x));
        List<CcrBallot> ballots = ballotMapper.selectList(new LambdaQueryWrapper<CcrBallot>()
                .eq(CcrBallot::getRoundId, roundId)
                .orderByAsc(CcrBallot::getPricingItemId)
                .orderByAsc(CcrBallot::getSubmitTime));
        Map<Long, List<CcrBallot>> byItem = ballots.stream().collect(Collectors.groupingBy(
                CcrBallot::getPricingItemId, LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> result = new ArrayList<>();
        byItem.forEach((pricingItemId, itemBallots) -> {
            List<Map<String, Object>> opinions = new ArrayList<>();
            for (CcrBallot ballot : itemBallots) {
                Map<String, Object> opinion = new LinkedHashMap<>();
                opinion.put("anonymNo", hashToAnonym.get(ballot.getVoterUserHash()));
                opinion.put("voteChoice", ballot.getVoteChoice());
                opinion.put("voteComment", ballot.getVoteComment());
                opinion.put("submitTime", ballot.getSubmitTime());
                opinions.add(opinion);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pricingItemId", pricingItemId);
            row.put("opinions", opinions);
            result.add(row);
        });
        return result;
    }

    // ---------- 私有 ----------

    /** 建批:批次+分项+6 人委员名单(冻结),入批分项置 VOTING */
    private CcrVoteRound doCreateRound(Long applicationId, List<Long> pricingItemIds, List<SysUserRead> members) {
        Integer maxNo = voteRoundMapper.selectList(new LambdaQueryWrapper<CcrVoteRound>()
                        .eq(CcrVoteRound::getApplicationId, applicationId))
                .stream().mapToInt(r -> r.getRoundNo() == null ? 0 : r.getRoundNo()).max().orElse(0);

        CcrVoteRound round = new CcrVoteRound();
        round.setApplicationId(applicationId);
        round.setRoundNo(maxNo + 1);
        round.setRoundName("表决批次" + (maxNo + 1));
        round.setVoterCount(DEFAULT_VOTER_COUNT);
        round.setRequiredCount(DEFAULT_REQUIRED_COUNT);
        round.setStatus("VOTING");
        round.setRoundStartTime(LocalDateTime.now());
        voteRoundMapper.insert(round);

        int seq = 1;
        for (Long pid : pricingItemIds) {
            CcrVoteRoundItem item = new CcrVoteRoundItem();
            item.setRoundId(round.getId());
            item.setPricingItemId(pid);
            item.setSequenceNo(seq++);
            roundItemMapper.insert(item);
            // 分项进入表决状态
            CcrPricingItem pricingItem = pricingItemMapper.selectById(pid);
            if (pricingItem != null) {
                pricingItem.setStatus(PricingItemStatus.VOTING.getCode());
                pricingItemMapper.updateById(pricingItem);
            }
        }

        String[] anonym = {"A", "B", "C", "D", "E", "F"};
        for (int i = 0; i < members.size(); i++) {
            CcrVoteAssignment assignment = new CcrVoteAssignment();
            assignment.setRoundId(round.getId());
            assignment.setVoterUserId(members.get(i).getId());
            assignment.setVoterAnonymNo(anonym[i]);
            assignment.setStatus("PENDING");
            assignmentMapper.insert(assignment);
        }
        log.info("申请 {} 表决批次 {} 创建,分项 {} 个", applicationId, round.getId(), pricingItemIds.size());
        return round;
    }

    /**
     * 单委员 assignment 完成标记:投完批次内全部分项(替补委员只要求原成员未投的剩余分项)后
     * 才置 SUBMITTED;多分项批次下不再"投一单即完成"
     */
    private void markAssignmentSubmittedIfDone(CcrVoteRound round, CcrVoteAssignment assignment) {
        List<CcrVoteRoundItem> items = roundItemMapper.selectList(new LambdaQueryWrapper<CcrVoteRoundItem>()
                .eq(CcrVoteRoundItem::getRoundId, round.getId()));
        String voterHash = voterHash(assignment.getVoterUserId());
        String fromHash = assignment.getSubstituteFromUserId() == null
                ? null : voterHash(assignment.getSubstituteFromUserId());
        boolean allDone = items.stream().allMatch(item ->
                ballotExists(round.getId(), item.getPricingItemId(), voterHash)
                        || (fromHash != null && ballotExists(round.getId(), item.getPricingItemId(), fromHash)));
        if (allDone && !items.isEmpty()) {
            assignment.setSubmitTime(LocalDateTime.now());
            assignment.setStatus("SUBMITTED");
            assignmentMapper.updateById(assignment);
        }
    }

    private boolean ballotExists(Long roundId, Long pricingItemId, String voterHash) {
        Long count = ballotMapper.selectCount(new LambdaQueryWrapper<CcrBallot>()
                .eq(CcrBallot::getRoundId, roundId)
                .eq(CcrBallot::getPricingItemId, pricingItemId)
                .eq(CcrBallot::getVoterUserHash, voterHash));
        return count != null && count > 0;
    }

    /** 分项粒度计票:该项收齐全部委员票后一次性生成结果;通过后按冻结路由进入行长决策或直接终审 */
    private void countItemIfReady(CcrVoteRound round, Long pricingItemId) {
        countItem(round, pricingItemId, false);
    }

    /**
     * 分项计票(§7.5):partial=false 需收齐全部委员票(正常路径);
     * partial=true 为超时强制计票(§7.5.5),按已投票数计,赞成≥requiredCount 通过否则不通过。
     * 计票结果同事务写 ccr_approval_action 留痕(from VOTING,to PRESIDENT_DECISION/FINAL/REJECTED)
     */
    private void countItem(CcrVoteRound round, Long pricingItemId, boolean partial) {
        Long submitted = ballotMapper.selectCount(new LambdaQueryWrapper<CcrBallot>()
                .eq(CcrBallot::getRoundId, round.getId())
                .eq(CcrBallot::getPricingItemId, pricingItemId));
        int submittedCount = submitted == null ? 0 : submitted.intValue();
        if (!partial && submittedCount < round.getVoterCount()) {
            return;
        }
        if (partial && submittedCount == 0) {
            // 超时且无任何投票:仍按 0 赞成计票(不通过),保证批次可关闭
            log.warn("分项 {} 批次 {} 超时且无任何投票,按不通过计票", pricingItemId, round.getId());
        }
        // 并发/重入防护:分项已有计票结果不再重复计票(uk_vote_result_pricing 兜底)
        Long counted = voteResultMapper.selectCount(new LambdaQueryWrapper<CcrVoteResult>()
                .eq(CcrVoteResult::getPricingItemId, pricingItemId));
        if (counted != null && counted > 0) {
            return;
        }
        Long approve = ballotMapper.selectCount(new LambdaQueryWrapper<CcrBallot>()
                .eq(CcrBallot::getRoundId, round.getId())
                .eq(CcrBallot::getPricingItemId, pricingItemId)
                .eq(CcrBallot::getVoteChoice, "APPROVE"));
        int approveCount = approve == null ? 0 : approve.intValue();
        boolean pass = approveCount >= round.getRequiredCount();

        CcrVoteResult result = new CcrVoteResult();
        result.setRoundId(round.getId());
        result.setPricingItemId(pricingItemId);
        result.setRequiredCount(round.getRequiredCount());
        result.setSubmittedCount(partial ? submittedCount : round.getVoterCount());
        result.setApproveCount(approveCount);
        result.setRejectCount(result.getSubmittedCount() - approveCount);
        result.setResult(pass ? "PASS" : "FAIL");
        result.setCountTime(LocalDateTime.now());
        try {
            voteResultMapper.insert(result);
        } catch (DuplicateKeyException e) {
            return;
        }

        // 通过后消费提交冻结的 president_required；未通过直接否决(终态,不恢复草稿 §B14)。
        CcrPricingItem item = pricingItemMapper.selectById(pricingItemId);
        if (item != null) {
            String countNote = (partial ? "超时计票:" : "计票:") + "赞成 " + result.getApproveCount()
                    + "/" + result.getSubmittedCount();
            if (pass) {
                boolean requiresPresident = FrozenRoutePlan.requiresPresident(item);
                item.setStatus(requiresPresident
                        ? PricingItemStatus.PRESIDENT_DECISION.getCode()
                        : PricingItemStatus.FINAL.getCode());
                if (!requiresPresident && item.getFinalRate() == null) {
                    item.setFinalRate(item.getCurrentApprovalRate());
                }
                updateItemWithLock(item);
                if (!requiresPresident) {
                    itemFinalizationService.afterItemTerminal(pricingItemId, "COMMITTEE_APPROVED");
                }
            } else {
                item.setStatus(PricingItemStatus.REJECTED.getCode());
                item.setFinalReason("六人小组表决未通过(" + countNote + ")");
                updateItemWithLock(item);
                itemFinalizationService.afterItemTerminal(pricingItemId, null);
            }
            // §14.7 流转留痕:计票动作(系统动作,operator_id 记 0)
            insertTrail(pricingItemId, pass ? "COUNT_PASS" : "COUNT_REJECT", "SIX_PEOPLE_GROUP",
                    0L, countNote + ",结果 " + result.getResult(),
                    PricingItemStatus.VOTING.getCode(), item.getStatus());
        }
        log.info("分项 {} 计票完成: 赞成{} 否决{}, 结果 {}", pricingItemId,
                result.getApproveCount(), result.getRejectCount(), result.getResult());

        // Warm-Flow 业务轨迹:计票结果(系统动作,operator 记 SYSTEM;失败仅记日志)
        warmFlowService.recordBusinessTrail(
                item == null ? String.valueOf(pricingItemId) : item.getPricingItemNo(),
                "SIX_PEOPLE_GROUP", pass ? "COUNT_PASS" : "COUNT_REJECT", "SYSTEM",
                "计票:赞成 " + result.getApproveCount() + "/" + result.getSubmittedCount()
                        + ",结果 " + result.getResult());

        closeRoundIfAllCounted(round);
    }

    /** ccr_approval_action 流转留痕(§14.7):仅插入,失败不阻断主流程 */
    private void insertTrail(Long pricingItemId, String actionType, String nodeCode, Long operatorId,
                             String comment, String fromStatus, String toStatus) {
        CcrApprovalActionTrail trail = new CcrApprovalActionTrail();
        trail.setPricingItemId(pricingItemId);
        trail.setTaskId(cn.hutool.core.util.IdUtil.fastSimpleUUID());
        trail.setActionType(actionType);
        trail.setNodeCode(nodeCode);
        trail.setOperatorId(operatorId);
        trail.setActionComment(comment);
        trail.setFromStatus(fromStatus);
        trail.setToStatus(toStatus);
        trail.setOperationChannel("PC");
        trail.setOperationTime(LocalDateTime.now());
        approvalActionTrailMapper.insert(trail);
    }

    /**
     * 批次关闭:批次内全部分项均有计票结果后才关闭(修复多分项批次提前关闭);
     * PASSED=任一分项通过 / FAILED=全部未过(分项粒度结论以 ccr_vote_result 为准)
     */
    private void closeRoundIfAllCounted(CcrVoteRound round) {
        Long itemCount = roundItemMapper.selectCount(new LambdaQueryWrapper<CcrVoteRoundItem>()
                .eq(CcrVoteRoundItem::getRoundId, round.getId()));
        List<CcrVoteResult> results = voteResultMapper.selectList(new LambdaQueryWrapper<CcrVoteResult>()
                .eq(CcrVoteResult::getRoundId, round.getId()));
        if (itemCount == null || results.size() < itemCount) {
            return;
        }
        boolean anyPass = results.stream().anyMatch(r -> "PASS".equals(r.getResult()));
        round.setStatus(anyPass ? "PASSED" : "FAILED");
        round.setRoundEndTime(LocalDateTime.now());
        voteRoundMapper.updateById(round);
        log.info("批次 {} 关闭,结果 {}", round.getId(), round.getStatus());
    }

    /** 乐观锁更新(updateById 由 @Version 拦截器带版本条件),0 行视为版本冲突 */
    private void updateItemWithLock(CcrPricingItem item) {
        int rows = pricingItemMapper.updateById(item);
        if (rows == 0) {
            throw new ServiceException(ErrorCode.DATA_VERSION_CONFLICT.getCode(), "分项数据版本冲突,请刷新后重试");
        }
    }

    private String voterHash(Long voterUserId) {
        return DigestUtil.sha256Hex(String.valueOf(voterUserId));
    }
}
