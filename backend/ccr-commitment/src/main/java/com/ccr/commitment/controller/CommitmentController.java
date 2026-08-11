package com.ccr.commitment.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.ccr.common.core.domain.R;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.commitment.domain.CcrCommitmentMemberAlloc;
import com.ccr.commitment.domain.CcrCommitmentMetric;
import com.ccr.commitment.domain.CcrCommitmentPlan;
import com.ccr.commitment.domain.CcrTrackingEvaluation;
import com.ccr.commitment.service.CommitmentQueryService;
import com.ccr.commitment.service.CommitmentService;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 创建计划请求 DTO(避免 Map 强转问题) */
@Data
class CreatePlanReq {
    private CcrCommitmentPlan plan;
    private List<CcrCommitmentMetric> metrics;
    /** 集团成员分配(GROUP+FIXED_ALLOCATION 必填,metricCode 关联指标) */
    private List<CcrCommitmentMemberAlloc> memberAllocs;
}

/**
 * 承诺跟踪接口(§11)
 */
@RestController
@RequestMapping("/ccr/commitments")
public class CommitmentController {

    @Resource
    private CommitmentService commitmentService;

    @Resource
    private CommitmentQueryService commitmentQueryService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 计划详情(§11.8:计划+指标+最新评估明细;数据权限同列表) */
    @GetMapping("/plans/{planId}")
    public R<Map<String, Object>> planDetail(@PathVariable Long planId) {
        return R.ok(commitmentQueryService.planDetail(planId));
    }

    /** 月报查询(§11.8:按月按机构聚合评估结果——计划数/达成率/风险分布;数据权限同列表) */
    @GetMapping("/monthly-report")
    public R<Map<String, Object>> monthlyReport(@RequestParam(required = false) String month,
                                                @RequestParam(required = false) Long orgId) {
        return R.ok(commitmentQueryService.monthlyReport(month, orgId));
    }

    /** 客户所属机构达成率(§11.8 D19:dw_org_performance 最新批次+本系统评估数据组装;数据权限同列表) */
    @GetMapping("/org-achievement")
    public R<Map<String, Object>> orgAchievement(@RequestParam String customerNo) {
        return R.ok(commitmentQueryService.orgAchievement(customerNo));
    }

    /** 审批通过后生成承诺计划 */
    @PostMapping("/plans")
    public R<CcrCommitmentPlan> createPlan(@RequestBody CreatePlanReq req) {
        CcrCommitmentPlan plan = req.getPlan();
        if (plan.getScopeType() == null) {
            plan.setScopeType("CORPORATE_SINGLE");
        }
        return R.ok(commitmentService.createPlan(plan, req.getMetrics(), req.getMemberAllocs()));
    }

    /** 人工状态变迁(TERMINATED/SUPERSEDED) */
    @PostMapping("/plans/{planId}/status")
    public R<CcrCommitmentPlan> changeStatus(@PathVariable Long planId, @RequestBody Map<String, Object> body) {
        return R.ok(commitmentService.changeStatus(
                planId,
                body.get("targetStatus") == null ? null : body.get("targetStatus").toString(),
                body.get("remark") == null ? null : body.get("remark").toString()));
    }

    /** 单指标履约计算 */
    @PostMapping("/evaluate")
    public R<CcrTrackingEvaluation> evaluate(@RequestBody Map<String, Object> body) {
        return R.ok(commitmentService.evaluate(
                Long.valueOf(body.get("metricId").toString()),
                LocalDate.parse(body.get("dataDt").toString()),
                new BigDecimal(body.get("actualValue").toString()),
                body.get("sourceBatch") == null ? "MOCK-BATCH" : body.get("sourceBatch").toString()));
    }

    /** 按计划批量履约(定时任务入口) */
    @PostMapping("/plans/{planId}/evaluate")
    public R<List<CcrTrackingEvaluation>> evaluatePlan(@PathVariable Long planId, @RequestBody Map<String, Object> body) {
        return R.ok(commitmentService.evaluatePlan(
                planId,
                LocalDate.parse(body.get("dataDt").toString()),
                body.get("sourceBatch") == null ? "MOCK-BATCH" : body.get("sourceBatch").toString()));
    }

    /** 保存指标跟踪描述(§6.4/§10.3.15:承诺类型"其它"手工跟踪留痕,track_desc 覆盖式更新) */
    @PostMapping("/metrics/{metricId}/track")
    public R<CcrCommitmentMetric> saveTrackDesc(@PathVariable Long metricId, @RequestBody Map<String, Object> body) {
        String trackDesc = body.get("trackDesc") == null ? null : body.get("trackDesc").toString();
        return R.ok(commitmentService.saveTrackDesc(metricId, trackDesc));
    }

    /**
     * 贡献度跟踪列表(数据权限,§5.4:身份/角色一律取登录态+用户表,不接受传参防越权):
     * 6人小组/总行行长/admin/审计 → 全部;客户经理 → 本人申请;普通审批人 → 本人审批过的客户
     */
    @GetMapping("/plans")
    public R<List<Map<String, Object>>> listPlans() {
        Long operatorId = StpUtil.getLoginIdAsLong();
        List<Map<String, Object>> users = jdbcTemplate.queryForList(
                "SELECT role_code roleCode FROM ccr_sys_user WHERE id = ? AND del_flag = '0'", operatorId);
        if (users.isEmpty()) {
            throw new ServiceException(ErrorCode.UNAUTHORIZED.getCode(), "登录用户不存在");
        }
        String roleCode = users.get(0).get("roleCode") == null ? null : users.get(0).get("roleCode").toString();
        String sql;
        if ("committee_member".equals(roleCode) || "president".equals(roleCode)
                || "admin".equals(roleCode) || "auditor".equals(roleCode)) {
            sql = """
                    SELECT cp.id, cp.plan_no, cp.scope_type, cp.customer_no, cp.status,
                           cm.id metric_id, cm.metric_code, cm.metric_name, cm.target_value, cm.track_desc,
                           te.actual_value, te.achievement_ratio, te.result_status
                    FROM ccr_commitment_plan cp
                    LEFT JOIN ccr_commitment_metric cm ON cm.plan_id = cp.id
                    LEFT JOIN (SELECT metric_id, MAX(data_dt) max_dt FROM ccr_tracking_evaluation GROUP BY metric_id) t
                              ON t.metric_id = cm.id
                    LEFT JOIN ccr_tracking_evaluation te ON te.metric_id = cm.id AND te.data_dt = t.max_dt
                    WHERE cp.del_flag = '0'
                    ORDER BY cp.create_time DESC
                    """;
            return R.ok(jdbcTemplate.queryForList(sql));
        }
        if ("customer_manager".equals(roleCode)) {
            sql = """
                    SELECT cp.id, cp.plan_no, cp.scope_type, cp.customer_no, cp.status,
                           cm.id metric_id, cm.metric_code, cm.metric_name, cm.target_value, cm.track_desc,
                           te.actual_value, te.achievement_ratio, te.result_status
                    FROM ccr_commitment_plan cp
                    JOIN ccr_resolution r ON r.id = cp.resolution_id
                    JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id
                    JOIN ccr_application a ON a.id = pi.application_id
                    LEFT JOIN ccr_commitment_metric cm ON cm.plan_id = cp.id
                    LEFT JOIN (SELECT metric_id, MAX(data_dt) max_dt FROM ccr_tracking_evaluation GROUP BY metric_id) t
                              ON t.metric_id = cm.id
                    LEFT JOIN ccr_tracking_evaluation te ON te.metric_id = cm.id AND te.data_dt = t.max_dt
                    WHERE cp.del_flag = '0' AND a.applicant_user_id = ?
                    ORDER BY cp.create_time DESC
                    """;
            return R.ok(jdbcTemplate.queryForList(sql, operatorId));
        }
        // 普通审批人:本人审批过的申请的客户
        sql = """
                SELECT cp.id, cp.plan_no, cp.scope_type, cp.customer_no, cp.status,
                       cm.metric_code, cm.metric_name, cm.target_value,
                       te.actual_value, te.achievement_ratio, te.result_status
                FROM ccr_commitment_plan cp
                JOIN ccr_resolution r ON r.id = cp.resolution_id
                JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id
                LEFT JOIN ccr_commitment_metric cm ON cm.plan_id = cp.id
                LEFT JOIN (SELECT metric_id, MAX(data_dt) max_dt FROM ccr_tracking_evaluation GROUP BY metric_id) t
                          ON t.metric_id = cm.id
                LEFT JOIN ccr_tracking_evaluation te ON te.metric_id = cm.id AND te.data_dt = t.max_dt
                WHERE cp.del_flag = '0'
                  AND pi.application_id IN (
                      SELECT pi2.application_id FROM ccr_approval_action aa
                      JOIN ccr_pricing_item pi2 ON pi2.id = aa.pricing_item_id
                      WHERE aa.operator_id = ? AND aa.del_flag = '0'
                  )
                ORDER BY cp.create_time DESC
                """;
        return R.ok(jdbcTemplate.queryForList(sql, operatorId));
    }
}
