package com.ccr.vote.controller;

import com.ccr.common.core.assignee.NodeAssigneeResolver;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    @Resource
    private NodeAssigneeResolver nodeAssigneeResolver;

    /** 行长待决策:表决通过(COMMITTEE_PASS/PRESIDENT_DECISION)待行长决策的申请(按申请聚合,与审批页一致);
     *  同一申请的多个分项合并为一个待决策项,items 内含分项明细;
     *  PRESIDENT 节点配置有效指派时,仅解析出的处理人可见(admin 审计视角不过滤) */
    @GetMapping("/president/todo")
    public R<List<Map<String, Object>>> presidentTodo() {
        currentLoginUser.requireAnyRole(CurrentLoginUser.ROLE_PRESIDENT, CurrentLoginUser.ROLE_ADMIN);
        String sql = """
                SELECT pi.application_id applicationId, a.application_no applicationNo,
                       a.business_type businessType, pi.pricing_customer_no customerNo,
                       pi.id pricingItemId, pi.pricing_item_no pricingItemNo,
                       pi.requested_rate requestedRate, pi.current_approval_rate approvalRate,
                       pi.pricing_amount pricingAmount, pi.product_code productCode,
                       pi.status status, a.applicant_org_id applicantOrgId,
                       COALESCE(vr.round_id,0) roundId,
                       COALESCE(vr.approve_count,0) approveCount, COALESCE(vr.reject_count,0) rejectCount
                FROM ccr_pricing_item pi
                JOIN ccr_application a ON a.id = pi.application_id
                LEFT JOIN ccr_vote_result vr ON vr.pricing_item_id = pi.id
                WHERE pi.status IN ('COMMITTEE_PASS','PRESIDENT_DECISION') AND pi.del_flag = '0'
                ORDER BY pi.create_time DESC
                """;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
        // 按申请聚合:同一申请全部分项合并为一个待决策项(行长决策为整单决策,与申请/审批页一致)
        Map<Long, Map<String, Object>> byApp = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long appId = ((Number) row.get("applicationId")).longValue();
            Map<String, Object> app = byApp.computeIfAbsent(appId, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("applicationId", appId);
                m.put("applicationNo", row.get("applicationNo"));
                m.put("businessType", row.get("businessType"));
                m.put("customerNo", row.get("customerNo"));
                m.put("applicantOrgId", row.get("applicantOrgId"));
                m.put("items", new ArrayList<Map<String, Object>>());
                return m;
            });
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pricingItemId", row.get("pricingItemId"));
            item.put("pricingItemNo", row.get("pricingItemNo"));
            item.put("requestedRate", row.get("requestedRate"));
            item.put("approvalRate", row.get("approvalRate"));
            item.put("pricingAmount", row.get("pricingAmount"));
            item.put("productCode", row.get("productCode"));
            item.put("status", row.get("status"));
            item.put("roundId", row.get("roundId"));
            item.put("approveCount", row.get("approveCount"));
            item.put("rejectCount", row.get("rejectCount"));
            ((List<Map<String, Object>>) app.get("items")).add(item);
        }
        List<Map<String, Object>> result = new ArrayList<>(byApp.values());
        if (CurrentLoginUser.ROLE_ADMIN.equals(currentLoginUser.currentRoleCode())) {
            result.forEach(row -> row.remove("applicantOrgId"));
            return R.ok(result);
        }
        Long userId = currentLoginUser.requireLoginId();
        result.removeIf(row -> {
            Object orgId = row.remove("applicantOrgId");
            List<Long> assignees = nodeAssigneeResolver.resolveUserIds("PRESIDENT",
                    orgId == null ? null : ((Number) orgId).longValue());
            return !assignees.isEmpty() && !assignees.contains(userId);
        });
        return R.ok(result);
    }

    /** 委员待办:本人待表决的批次分项(匿名编号;只查本人 assignment,不泄露他人进度)
     *  只返回本人尚未表决的分项:已投过(ballot 已存在)与已投完(assignment SUBMITTED/REPLACED)的均不显示,
     *  避免委员在工作台看到已表决项重复表决 */
    @GetMapping("/vote-rounds/todo")
    public R<List<Map<String, Object>>> todo() {
        Long voterUserId = currentLoginUser.requireLoginId();
        String sql = """
                SELECT va.round_id roundId, va.voter_anonym_no anonymNo, va.status assignStatus,
                       pi.application_id applicationId, a.application_no applicationNo,
                       a.business_type businessType, pi.pricing_customer_no customerNo,
                       pi.id pricingItemId, pi.pricing_item_no pricingItemNo,
                       pi.requested_rate requestedRate, pi.original_rate originalRate,
                       pi.pricing_amount pricingAmount, pi.product_code productCode,
                       pi.current_node_code currentNodeCode, pi.create_time createTime,
                       (SELECT gp.main_guarantee_type FROM ccr_guarantee_package gp
                        WHERE gp.pricing_item_id = pi.id AND gp.del_flag = '0' LIMIT 1) guaranteeType
                FROM ccr_vote_assignment va
                JOIN ccr_vote_round_item ri ON ri.round_id = va.round_id
                JOIN ccr_pricing_item pi ON pi.id = ri.pricing_item_id
                JOIN ccr_application a ON a.id = pi.application_id
                WHERE va.voter_user_id = ? AND va.status = 'PENDING' AND va.del_flag = '0'
                  AND pi.status = 'VOTING'
                  AND NOT EXISTS (SELECT 1 FROM ccr_ballot b
                                  WHERE b.round_id = va.round_id
                                    AND b.pricing_item_id = pi.id
                                    AND b.voter_user_hash = SHA2(?, 256))
                  AND EXISTS (SELECT 1 FROM ccr_vote_round r
                              WHERE r.id = va.round_id AND r.status IN ('CREATED','VOTING','COUNTING'))
                ORDER BY va.create_time DESC
                """;
        return R.ok(jdbcTemplate.queryForList(sql, voterUserId, voterUserId));
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

    /** 行长查看批次委员匿名意见(§12.7,仅行长/审计;按分项返回匿名码+票型+意见,不含真实身份) */
    @GetMapping("/vote-rounds/{roundId}/opinions")
    public R<List<Map<String, Object>>> roundOpinions(@PathVariable Long roundId) {
        try {
            currentLoginUser.requireAnyRole(CurrentLoginUser.ROLE_PRESIDENT,
                    CurrentLoginUser.ROLE_ADMIN, CurrentLoginUser.ROLE_AUDITOR);
        } catch (ServiceException e) {
            throw new ServiceException(ErrorCode.FORBIDDEN.getCode(), "委员意见仅行长/审计可见");
        }
        return R.ok(voteService.listRoundOpinions(roundId));
    }

    /** 行长决策(整单:同意利率/一票否决,按申请一并决策);Idempotency-Key 头可选(唯一约束兜底) */
    @PostMapping("/president/decisions")
    public R<Void> presidentDecision(@RequestBody Map<String, Object> body) {
        voteService.presidentDecision(
                Long.valueOf(body.get("applicationId").toString()),
                body.get("decision").toString(),
                body.get("opinion") == null ? null : body.get("opinion").toString());
        return R.ok();
    }
}
