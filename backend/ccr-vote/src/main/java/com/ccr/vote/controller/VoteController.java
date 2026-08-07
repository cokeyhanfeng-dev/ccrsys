package com.ccr.vote.controller;

import com.ccr.common.core.domain.R;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.vote.domain.CcrVoteAssignment;
import com.ccr.vote.domain.CcrVoteResult;
import com.ccr.vote.domain.CcrVoteRound;
import com.ccr.vote.service.VoteService;
import com.ccr.vote.support.CurrentLoginUser;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 六人表决与行长决策接口(§13.2 审批接口)
 * 身份口径:委员/行长/替补发起人均取 Sa-Token 登录人,不再接受 voterUserId/presidentUserId 传参。
 * 注:工作流任务 taskId 接入 Warm-Flow 后映射,首期以 roundId 直连
 */
@RestController
@RequestMapping("/ccr")
public class VoteController {

    @Resource
    private VoteService voteService;

    @Resource
    private CurrentLoginUser currentLoginUser;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 行长待决策:表决通过(COMMITTEE_PASS/PRESIDENT_DECISION)待行长决策的分项(仅行长可见) */
    @GetMapping("/president/todo")
    public R<List<Map<String, Object>>> presidentTodo() {
        currentLoginUser.requireAnyRole(CurrentLoginUser.ROLE_PRESIDENT, CurrentLoginUser.ROLE_ADMIN);
        String sql = """
                SELECT pi.id pricingItemId, pi.pricing_item_no pricingItemNo,
                       pi.pricing_customer_no customerNo, pi.requested_rate requestedRate,
                       pi.current_approval_rate approvalRate, pi.status status,
                       COALESCE(vr.approve_count,0) approveCount, COALESCE(vr.reject_count,0) rejectCount
                FROM ccr_pricing_item pi
                LEFT JOIN ccr_vote_result vr ON vr.pricing_item_id = pi.id
                WHERE pi.status IN ('COMMITTEE_PASS','PRESIDENT_DECISION') AND pi.del_flag = '0'
                ORDER BY pi.create_time DESC
                """;
        return R.ok(jdbcTemplate.queryForList(sql));
    }

    /** 委员待办:本人待表决的批次分项(匿名编号;只查本人 assignment,不泄露他人进度) */
    @GetMapping("/vote-rounds/todo")
    public R<List<Map<String, Object>>> todo() {
        Long voterUserId = currentLoginUser.requireLoginId();
        String sql = """
                SELECT va.round_id roundId, va.voter_anonym_no anonymNo, va.status assignStatus,
                       ri.pricing_item_id pricingItemId, pi.requested_rate requestedRate,
                       pi.pricing_amount pricingAmount, pi.product_code productCode
                FROM ccr_vote_assignment va
                JOIN ccr_vote_round_item ri ON ri.round_id = va.round_id
                JOIN ccr_pricing_item pi ON pi.id = ri.pricing_item_id
                WHERE va.voter_user_id = ? AND va.status <> 'REPLACED' AND va.del_flag = '0'
                ORDER BY va.create_time DESC
                """;
        return R.ok(jdbcTemplate.queryForList(sql, voterUserId));
    }

    /** 委员提交本人票(一人一票);Idempotency-Key 头可选 */
    @PostMapping("/vote-rounds/{roundId}/ballots")
    public R<Void> submitBallot(@PathVariable Long roundId,
                                @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                @RequestBody Map<String, Object> body) {
        voteService.submitBallot(
                roundId,
                Long.valueOf(body.get("pricingItemId").toString()),
                body.get("choice").toString(),
                body.get("comment") == null ? null : body.get("comment").toString(),
                idempotencyKey);
        return R.ok();
    }

    /** 本人选择与提交结果(只返回本人票型) */
    @GetMapping("/vote-rounds/{roundId}/ballots/my")
    public R<Map<String, Object>> myBallot(@PathVariable Long roundId, @RequestParam Long pricingItemId) {
        return R.ok(voteService.myBallot(roundId, pricingItemId));
    }

    /** 委员替补(§7.4,授权角色:行长或流程管理员) */
    @PostMapping("/vote-rounds/{roundId}/substitute")
    public R<CcrVoteAssignment> substitute(@PathVariable Long roundId, @RequestBody Map<String, Object> body) {
        return R.ok(voteService.substitute(
                roundId,
                Long.valueOf(body.get("fromUserId").toString()),
                Long.valueOf(body.get("toUserId").toString()),
                body.get("reason") == null ? null : body.get("reason").toString()));
    }

    /** 查询分项计票结果(匿名性:仅行长/审计(admin)可查,普通用户拒绝) */
    @GetMapping("/vote-results/{pricingItemId}")
    public R<CcrVoteResult> voteResult(@PathVariable Long pricingItemId) {
        try {
            currentLoginUser.requireAnyRole(CurrentLoginUser.ROLE_PRESIDENT, CurrentLoginUser.ROLE_ADMIN);
        } catch (ServiceException e) {
            throw new ServiceException(ErrorCode.FORBIDDEN.getCode(), "计票结果仅行长/审计可见");
        }
        return R.ok(voteService.getVoteResult(pricingItemId));
    }

    /** 行长决策(同意利率/一票否决);Idempotency-Key 头可选(唯一约束兜底) */
    @PostMapping("/president/decisions")
    public R<Void> presidentDecision(@RequestBody Map<String, Object> body) {
        voteService.presidentDecision(
                Long.valueOf(body.get("pricingItemId").toString()),
                body.get("decision").toString(),
                body.get("opinion") == null ? null : body.get("opinion").toString());
        return R.ok();
    }
}
