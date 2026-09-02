package com.ccr.vote.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.domain.CcrPricingItem;
import com.ccr.application.enums.PricingItemStatus;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.application.mapper.CcrPricingItemMapper;
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
import java.util.Collections;
import java.util.Comparator;
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
    public void submitBallot(Long roundId, Long applicationId, String choice, String comment, String idempotencyKey) {
        if (!"APPROVE".equals(choice) && !"REJECT".equals(choice)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "票型只能为 APPROVE/REJECT");
        }
        CcrVoteRound round = voteRoundMapper.selectByIdForUpdate(roundId);
        if (round == null || !"VOTING".equals(round.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(), "批次不在表决中");
        }
        // 整单化:批与申请一一对应,校验 applicationId 属于该批次
        if (applicationId == null || !applicationId.equals(round.getApplicationId())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "申请不属于该表决批次");
        }
        // 批内锚定分项(整单化:代表整单落票据/计票/留痕)
        CcrPricingItem anchor = anchorItemOf(round);
        if (anchor == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "批次无待表决分项");
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
        // 整单化一席位一票:原委员已投则替补不再投(uk_ballot 仅按投票人 hash 防重,须显式防重)
        String myHash = voterHash(voterUserId);
        boolean seatVoted = ballotExists(roundId, anchor.getId(), myHash);
        if (!seatVoted && assignment.getSubstituteFromUserId() != null) {
            seatVoted = ballotExists(roundId, anchor.getId(),
                    voterHash(assignment.getSubstituteFromUserId()));
        }
        if (seatVoted) {
            throw new ServiceException(ErrorCode.DUPLICATE_VOTE.getCode(), "重复投票:本席位已投,每人每批只能投一次");
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
        ballot.setPricingItemId(anchor.getId());
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
            throw new ServiceException(ErrorCode.DUPLICATE_VOTE.getCode(), "重复投票:每人每批只能投一次");
        }

        // 整单化:投一票即本委员本批完成(一个申请一个批一个整单票)
        assignment.setSubmitTime(LocalDateTime.now());
        assignment.setStatus("SUBMITTED");
        assignmentMapper.updateById(assignment);
        // Warm-Flow 业务轨迹:投票提交(操作人传登录人id字符串;失败仅记日志,不阻断主流程)
        warmFlowService.recordBusinessTrail(
                String.valueOf(applicationId), "SIX_PEOPLE_GROUP", choice, String.valueOf(voterUserId), comment);
        // 整单计票:本批全部委员票收齐后一次性计票(全员投完才见结果)
        if (isRoundAllVoted(round)) {
            countAllItems(round);
        }
    }

    /** 批内锚定分项(整单化:批=申请,取批内第一个分项代表整单落票据/计票/留痕) */
    private CcrPricingItem anchorItemOf(CcrVoteRound round) {
        List<CcrVoteRoundItem> items = roundItemMapper.selectList(new LambdaQueryWrapper<CcrVoteRoundItem>()
                .eq(CcrVoteRoundItem::getRoundId, round.getId())
                .orderByAsc(CcrVoteRoundItem::getPricingItemId)
                .last("limit 1"));
        return items.isEmpty() ? null : pricingItemMapper.selectById(items.get(0).getPricingItemId());
    }

    @Override
    public Map<String, Object> myBallot(Long roundId, Long applicationId) {
        Long voterUserId = currentLoginUser.requireLoginId();
        CcrVoteRound round = voteRoundMapper.selectById(roundId);
        CcrPricingItem anchor = round == null ? null : anchorItemOf(round);
        if (anchor == null) {
            return null;
        }
        CcrBallot ballot = ballotMapper.selectOne(new LambdaQueryWrapper<CcrBallot>()
                .eq(CcrBallot::getRoundId, roundId)
                .eq(CcrBallot::getPricingItemId, anchor.getId())
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
    public CcrVoteResult getVoteResult(Long applicationId) {
        // 整单化:按申请最新表决批次取整单计票结果
        CcrVoteRound round = voteRoundMapper.selectOne(new LambdaQueryWrapper<CcrVoteRound>()
                .eq(CcrVoteRound::getApplicationId, applicationId)
                .orderByDesc(CcrVoteRound::getCreateTime)
                .last("limit 1"));
        if (round == null) {
            return null;
        }
        return voteResultMapper.selectOne(new LambdaQueryWrapper<CcrVoteResult>()
                .eq(CcrVoteResult::getRoundId, round.getId())
                .last("limit 1"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void presidentDecision(Long applicationId, String decision, String opinion) {
        if (!"APPROVE".equals(decision) && !"VETO".equals(decision)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "决策只能为 APPROVE/VETO");
        }
        if ("VETO".equals(decision) && StrUtil.isBlank(opinion)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "一票否决必须填写意见(§7.5)");
        }
        // 行长决策校验 PRESIDENT 角色(身份取登录人)
        currentLoginUser.requireAnyRole(CurrentLoginUser.ROLE_PRESIDENT);
        Long presidentUserId = currentLoginUser.requireLoginId();

        // 整单决策(§用户拍板):该申请下所有待行长决策分项一并决策,与审批页整单口径一致
        List<CcrPricingItem> pendingItems = pricingItemMapper.selectList(
                new LambdaQueryWrapper<CcrPricingItem>()
                        .eq(CcrPricingItem::getApplicationId, applicationId)
                        .and(w -> w.eq(CcrPricingItem::getStatus, PricingItemStatus.COMMITTEE_PASS.getCode())
                                .or().eq(CcrPricingItem::getStatus, PricingItemStatus.PRESIDENT_DECISION.getCode()))
                        .eq(CcrPricingItem::getDelFlag, "0"));
        if (pendingItems.isEmpty()) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(), "该申请无待行长决策分项(§7.5)");
        }
        // 节点审批人配置限制(§5.5.1):PRESIDENT 节点配置有效指派时,仅解析出的处理人可决策
        List<Long> presidentAssignees = nodeAssigneeResolver.resolveUserIds("PRESIDENT",
                applicantOrgId(applicationId));
        if (!presidentAssignees.isEmpty() && !presidentAssignees.contains(presidentUserId)) {
            throw new ServiceException(ErrorCode.NODE_PERMISSION.getCode(),
                    "PRESIDENT节点已配置指定决策人,当前登录人不在指派范围内");
        }

        for (CcrPricingItem item : pendingItems) {
            decideOneItem(item, decision, opinion, presidentUserId);
        }
        log.info("申请 {} 行长决策 {} 完成,共 {} 个分项", applicationId, decision, pendingItems.size());
    }

    /** 单分项行长决策:落库决策记录 + 同意→FINAL/否决→VETOED(整单循环复用,幂等由 uk_president_pricing 兜底) */
    private void decideOneItem(CcrPricingItem item, String decision, String opinion, Long presidentUserId) {
        Long pricingItemId = item.getId();
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
        // §D-7 兼岗:替补委员可为 role_code=committee_member 或六人小组配置名单中的兼岗用户(如部门总经理兼委员)
        if (!CurrentLoginUser.ROLE_COMMITTEE.equals(toUser.getRoleCode())
                && !nodeAssigneeResolver.isUserInAssignees("SIX_PEOPLE_GROUP", toUser.getId())) {
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
     * (含 PRESIDENT_DECISION 流转与批次关闭);整单化后按批一次计票
     *
     * @return 本次强制计票的批次数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int scanTimeoutRounds() {
        LocalDateTime deadline = LocalDateTime.now().minusHours(roundTimeoutHours);
        List<CcrVoteRound> expired = voteRoundMapper.selectList(new LambdaQueryWrapper<CcrVoteRound>()
                .eq(CcrVoteRound::getStatus, "VOTING")
                .lt(CcrVoteRound::getRoundStartTime, deadline));
        int count = 0;
        for (CcrVoteRound expiredRound : expired) {
            // 统一锁序(round→分项):先锁批次行,与 submitBallot 一致,避免并发死锁
            CcrVoteRound round = voteRoundMapper.selectByIdForUpdate(expiredRound.getId());
            CcrPricingItem anchor = anchorItemOf(round);
            if (anchor == null) {
                continue;
            }
            Long existed = voteResultMapper.selectCount(new LambdaQueryWrapper<CcrVoteResult>()
                    .eq(CcrVoteResult::getPricingItemId, anchor.getId()));
            if (existed != null && existed > 0) {
                continue;
            }
            countItem(round, anchor.getId(), true);
            count++;
        }
        if (count > 0) {
            log.info("表决超时扫描完成,强制计票批次 {} 个", count);
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
                String no = hashToAnonym.get(ballot.getVoterUserHash());
                opinion.put("anonymNo", no);
                opinion.put("seq", anonymSeq(no));
                opinion.put("voteChoice", ballot.getVoteChoice());
                opinion.put("voteComment", ballot.getVoteComment());
                opinion.put("submitTime", ballot.getSubmitTime());
                opinions.add(opinion);
            }
            // 行长决策页固定按小组成员序号 1..6 顺序展示(存贷款利率审批小组成员 N),不受提交先后影响
            opinions.sort(Comparator.comparingInt(o -> (Integer) o.get("seq")));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pricingItemId", pricingItemId);
            row.put("opinions", opinions);
            result.add(row);
        });
        return result;
    }

    // ---------- 私有 ----------

    /** 匿名代号 A-F → 小组成员序号 1-6(行长决策页「存贷款利率审批小组成员 N」固定顺序;未知代号归末尾) */
    private int anonymSeq(String no) {
        if (no == null || no.isEmpty()) return 99;
        char c = no.charAt(0);
        return (c >= 'A' && c <= 'F') ? (c - 'A' + 1) : 99;
    }

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

        // 匿名代号每次批次随机打乱分配(§12.7 匿名口径):同一委员不同批次匿名代号不固定,
        // 避免"A 一直对应某人"导致匿名可追溯;代号仅存本批次 assignment,不跨批次复用
        String[] anonym = {"A", "B", "C", "D", "E", "F"};
        List<String> shuffled = new ArrayList<>(List.of(anonym));
        Collections.shuffle(shuffled);
        for (int i = 0; i < members.size(); i++) {
            CcrVoteAssignment assignment = new CcrVoteAssignment();
            assignment.setRoundId(round.getId());
            assignment.setVoterUserId(members.get(i).getId());
            assignment.setVoterAnonymNo(shuffled.get(i));
            assignment.setStatus("PENDING");
            assignmentMapper.insert(assignment);
        }
        log.info("申请 {} 表决批次 {} 创建,分项 {} 个", applicationId, round.getId(), pricingItemIds.size());
        return round;
    }

    private boolean ballotExists(Long roundId, Long pricingItemId, String voterHash) {
        Long count = ballotMapper.selectCount(new LambdaQueryWrapper<CcrBallot>()
                .eq(CcrBallot::getRoundId, roundId)
                .eq(CcrBallot::getPricingItemId, pricingItemId)
                .eq(CcrBallot::getVoterUserHash, voterHash));
        return count != null && count > 0;
    }

    /**
     * 整单投票是否收齐(整单交付改造 2026-08-29:一批=一申请=一个整单票,
     * 每席位一票由 submitBallot 防重保证,本批 ballot 总数达到委员数即全员投完)。
     */
    private boolean isRoundAllVoted(CcrVoteRound round) {
        Long total = ballotMapper.selectCount(new LambdaQueryWrapper<CcrBallot>()
                .eq(CcrBallot::getRoundId, round.getId()));
        return total != null && total >= round.getVoterCount();
    }

    /** 整单计票(整单交付改造 2026-08-29):一批一次计票(批=申请,结果作用于批内全部分项,同事务) */
    private void countAllItems(CcrVoteRound round) {
        CcrPricingItem anchor = anchorItemOf(round);
        if (anchor == null) {
            return;
        }
        countItem(round, anchor.getId(), false);
    }

    /**
     * 整单计票(§7.5):partial=false 需收齐全部委员票(正常路径);
     * partial=true 为超时强制计票(§7.5.5),按已投票数计,赞成≥requiredCount 通过否则不通过。
     * 计票结果作用于批内全部分项(通过→整单 PRESIDENT_DECISION 待行长决策;否决→整单 REJECTED 连坐),
     * 同事务写 ccr_approval_action 留痕(from VOTING,to PRESIDENT_DECISION/REJECTED)
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
            log.warn("申请 {} 批次 {} 超时且无任何投票,按不通过计票", round.getApplicationId(), round.getId());
        }
        // 并发/重入防护:整单已有计票结果不再重复计票(uk_vote_result_pricing 按锚定分项兜底)
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

        // 整单化:计票结果作用于批内全部分项(整单一起置态,通过→行长决策,未通过→整单否决)
        List<CcrVoteRoundItem> roundItems = roundItemMapper.selectList(new LambdaQueryWrapper<CcrVoteRoundItem>()
                .eq(CcrVoteRoundItem::getRoundId, round.getId()));
        List<Long> roundItemIds = roundItems.stream().map(CcrVoteRoundItem::getPricingItemId)
                .collect(Collectors.toList());
        List<CcrPricingItem> allItems = roundItemIds.isEmpty() ? List.of()
                : pricingItemMapper.selectBatchIds(roundItemIds);
        String countNote = (partial ? "超时计票:" : "计票:") + "赞成 " + result.getApproveCount()
                + "/" + result.getSubmittedCount();
        for (CcrPricingItem item : allItems) {
            if (pass) {
                item.setStatus(PricingItemStatus.PRESIDENT_DECISION.getCode());
                updateItemWithLock(item);
            } else {
                item.setStatus(PricingItemStatus.REJECTED.getCode());
                item.setFinalReason("六人小组表决未通过(" + countNote + ")");
                updateItemWithLock(item);
            }
            // §14.7 流转留痕:计票动作(系统动作,operator_id 记 0)
            insertTrail(item.getId(), pass ? "COUNT_PASS" : "COUNT_REJECT", "SIX_PEOPLE_GROUP",
                    0L, countNote + ",结果 " + ("PASS".equals(result.getResult()) ? "通过" : "未通过"),
                    PricingItemStatus.VOTING.getCode(), item.getStatus());
        }
        // 小组否决 → 整单生成否决决议(决议书,不建承诺计划;整单化按锚定分项触发一次)
        if (!pass) {
            itemFinalizationService.afterItemTerminal(pricingItemId, "COMMITTEE_REJECT");
        }
        log.info("申请 {} 计票完成: 赞成{} 否决{}, 结果 {}", round.getApplicationId(),
                result.getApproveCount(), result.getRejectCount(), result.getResult());

        // Warm-Flow 业务轨迹:计票结果(系统动作,operator 记 SYSTEM;失败仅记日志)
        warmFlowService.recordBusinessTrail(
                String.valueOf(round.getApplicationId()), "SIX_PEOPLE_GROUP",
                pass ? "COUNT_PASS" : "COUNT_REJECT", "SYSTEM",
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
     * 批次关闭(整单交付改造 2026-08-29):一批一次计票,计票完成即关闭;
     * PASSED=通过 / FAILED=未过(整单结论以 ccr_vote_result 为准)
     */
    private void closeRoundIfAllCounted(CcrVoteRound round) {
        CcrVoteResult result = voteResultMapper.selectOne(new LambdaQueryWrapper<CcrVoteResult>()
                .eq(CcrVoteResult::getRoundId, round.getId())
                .last("limit 1"));
        if (result == null) {
            return;
        }
        round.setStatus("PASS".equals(result.getResult()) ? "PASSED" : "FAILED");
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
