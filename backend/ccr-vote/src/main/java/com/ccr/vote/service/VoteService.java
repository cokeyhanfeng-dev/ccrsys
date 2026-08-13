package com.ccr.vote.service;

import com.ccr.vote.domain.CcrVoteAssignment;
import com.ccr.vote.domain.CcrVoteResult;
import com.ccr.vote.domain.CcrVoteRound;

import java.util.List;
import java.util.Map;

/**
 * 六人表决与行长决策服务(§7.4/§7.5)
 * 身份口径:投票人/行长/替补发起人均取 Sa-Token 登录人,不接受请求传参
 */
public interface VoteService {

    /**
     * 自动合批建表决批次(§7.4):同申请当前处于小组节点、且未入任何进行中批次的分项合为一批;
     * 已在进行中批次的分项不受影响,后续到达小组节点的分项入新批。
     * 批次创建冻结 6 人名单(系统角色为小组成员的启用用户取 6 人,不足 6 人抛配置错误),入批分项置 VOTING。
     *
     * @return 新建批次;无可合批分项返回 null
     */
    CcrVoteRound createGroupRound(Long applicationId);

    /**
     * 委员提交本人票(一人一票,提交后不可修改)
     * 校验:(roundId,pricingItemId) 属于该批次;登录人为本批次未替补委员
     *
     * @param idempotencyKey 幂等键(可空),重复键抛 IDEMPOTENCY_REPEAT
     */
    void submitBallot(Long roundId, Long pricingItemId, String choice, String comment, String idempotencyKey);

    /**
     * 查询本人选择与提交结果(只返回本人票型,不泄露他人投票)
     *
     * @return voteChoice/voteComment/submitTime;未投票返回 null
     */
    Map<String, Object> myBallot(Long roundId, Long pricingItemId);

    /**
     * 查询分项计票结果(匿名口径:仅汇总票数,不返回投票人;接口层仅放行行长/审计角色)
     */
    CcrVoteResult getVoteResult(Long pricingItemId);

    /**
     * 行长决策(§7.5,整单):按申请决策——该申请下所有待行长决策分项
     * (状态 COMMITTEE_PASS/PRESIDENT_DECISION)一并同意/否决,与审批页整单口径一致;
     * 登录人须为总行行长;重复决策由 uk_president_pricing 唯一约束兜底(TASK_PROCESSED)
     */
    void presidentDecision(Long applicationId, String decision, String opinion);

    /**
     * 委员替补(§7.4):授权角色为行长或流程管理员(admin)。
     * 原 assignment 置 REPLACED,新建 assignment 记录 substitute_from_user_id/substitute_reason;
     * 原成员已投票据保留有效,替补委员只投原成员未投的剩余分项。
     *
     * @return 新建替补 assignment
     */
    CcrVoteAssignment substitute(Long roundId, Long fromUserId, Long toUserId, String reason);

    /**
     * 表决超时强制计票(§7.5.5):VOTING 批次超过配置时长(ccr.vote.round-timeout-hours,默认 72h)
     * 按已投票数计票,赞成≥requiredCount 通过否则不通过;结果落库与正常计票一致(含 PRESIDENT_DECISION 流转)
     *
     * @return 本次强制计票的分项数
     */
    int scanTimeoutRounds();

    /**
     * 批次委员匿名意见(§12.7,仅行长/审计可查):按分项返回 [{anonymNo, voteChoice, voteComment, submitTime}],
     * 不含真实身份(匿名码经票据哈希与批次名单映射,不反解用户)
     */
    List<Map<String, Object>> listRoundOpinions(Long roundId);
}
