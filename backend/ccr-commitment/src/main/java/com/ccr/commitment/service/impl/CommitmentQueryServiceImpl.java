package com.ccr.commitment.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.commitment.domain.CcrCommitmentMetric;
import com.ccr.commitment.domain.CcrCommitmentPlan;
import com.ccr.commitment.domain.CcrTrackingEvaluation;
import com.ccr.commitment.mapper.CcrCommitmentMetricMapper;
import com.ccr.commitment.mapper.CcrCommitmentPlanMapper;
import com.ccr.commitment.mapper.CcrTrackingEvaluationMapper;
import com.ccr.commitment.service.CommitmentQueryService;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 承诺查询实现(§11.8;数据权限口径与 listPlans 一致,角色取登录态+用户表)
 */
@Service
public class CommitmentQueryServiceImpl implements CommitmentQueryService {

    private static final Pattern MONTH_PATTERN = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    @Resource
    private CcrCommitmentPlanMapper planMapper;
    @Resource
    private CcrCommitmentMetricMapper metricMapper;
    @Resource
    private CcrTrackingEvaluationMapper evaluationMapper;
    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 登录用户数据范围(与 listPlans 同口径) */
    private record Scope(boolean fullView, boolean customerManager, Long operatorId) {
    }

    private Scope currentScope() {
        Long operatorId = StpUtil.getLoginIdAsLong();
        List<Map<String, Object>> users = jdbcTemplate.queryForList(
                "SELECT role_code roleCode FROM ccr_sys_user WHERE id = ? AND del_flag = '0'", operatorId);
        if (users.isEmpty()) {
            throw new ServiceException(ErrorCode.UNAUTHORIZED.getCode(), "登录用户不存在");
        }
        Object role = users.get(0).get("roleCode");
        String roleCode = role == null ? null : role.toString();
        boolean fullView = "committee_member".equals(roleCode) || "president".equals(roleCode)
                || "admin".equals(roleCode) || "auditor".equals(roleCode);
        return new Scope(fullView, "customer_manager".equals(roleCode), operatorId);
    }

    /**
     * 计划可见性过滤片段(配合 LEFT JOIN ccr_resolution r / ccr_pricing_item pi / ccr_application a 使用)
     */
    private String scopeCondition(Scope scope, List<Object> params) {
        if (scope.fullView()) {
            return "1=1";
        }
        if (scope.customerManager()) {
            params.add(scope.operatorId());
            return "a.applicant_user_id = ?";
        }
        params.add(scope.operatorId());
        return """
                pi.application_id IN (
                    SELECT pi2.application_id FROM ccr_approval_action aa
                    JOIN ccr_pricing_item pi2 ON pi2.id = aa.pricing_item_id
                    WHERE aa.operator_id = ? AND aa.del_flag = '0'
                )""";
    }

    /** 计划/评估关联申请的公共 FROM+JOIN 片段 */
    private static final String PLAN_EVAL_FROM = """
            FROM ccr_tracking_evaluation te
            JOIN ccr_commitment_plan cp ON cp.id = te.plan_id AND cp.del_flag = '0'
            LEFT JOIN ccr_resolution r ON r.id = cp.resolution_id
            LEFT JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id
            LEFT JOIN ccr_application a ON a.id = pi.application_id
            """;

    @Override
    public Map<String, Object> planDetail(Long planId) {
        Scope scope = currentScope();
        CcrCommitmentPlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "承诺计划不存在: " + planId);
        }
        List<Object> params = new ArrayList<>();
        params.add(planId);
        String condition = scopeCondition(scope, params);
        Long visible = jdbcTemplate.queryForObject("""
                SELECT COUNT(1) FROM ccr_commitment_plan cp
                LEFT JOIN ccr_resolution r ON r.id = cp.resolution_id
                LEFT JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id
                LEFT JOIN ccr_application a ON a.id = pi.application_id
                WHERE cp.id = ? AND cp.del_flag = '0' AND """ + condition, Long.class, params.toArray());
        if (visible == null || visible == 0) {
            throw new ServiceException(ErrorCode.FORBIDDEN.getCode(), "无权限查看该承诺计划");
        }
        List<CcrCommitmentMetric> metrics = metricMapper.selectList(new LambdaQueryWrapper<CcrCommitmentMetric>()
                .eq(CcrCommitmentMetric::getPlanId, planId)
                .orderByAsc(CcrCommitmentMetric::getId));
        List<Map<String, Object>> items = new ArrayList<>();
        for (CcrCommitmentMetric metric : metrics) {
            CcrTrackingEvaluation latest = evaluationMapper.selectOne(new LambdaQueryWrapper<CcrTrackingEvaluation>()
                    .eq(CcrTrackingEvaluation::getMetricId, metric.getId())
                    .orderByDesc(CcrTrackingEvaluation::getDataDt)
                    .orderByDesc(CcrTrackingEvaluation::getId)
                    .last("LIMIT 1"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("metric", metric);
            item.put("latestEvaluation", latest);
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("plan", plan);
        result.put("items", items);
        return result;
    }

    @Override
    public Map<String, Object> monthlyReport(String month, Long orgId) {
        Scope scope = currentScope();
        if (StrUtil.isBlank(month)) {
            month = YearMonth.now().toString();
        }
        if (!MONTH_PATTERN.matcher(month).matches()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "month 格式应为 YYYY-MM");
        }
        List<Object> params = new ArrayList<>();
        params.add(month);
        StringBuilder where = new StringBuilder("WHERE te.del_flag = '0' AND DATE_FORMAT(te.data_dt, '%Y-%m') = ?");
        if (orgId != null) {
            where.append(" AND cp.org_id = ?");
            params.add(orgId);
        }
        where.append(" AND ").append(scopeCondition(scope, params));

        Map<String, Object> summary = jdbcTemplate.queryForMap("""
                SELECT COUNT(DISTINCT te.plan_id) AS planCount, COUNT(1) AS evaluationCount,
                       AVG(te.achievement_ratio) AS avgAchievementRatio
                """ + PLAN_EVAL_FROM + where, params.toArray());

        List<Map<String, Object>> riskDistribution = jdbcTemplate.queryForList("""
                SELECT COALESCE(te.risk_level, 'UNKNOWN') AS riskLevel, COUNT(1) AS evaluationCount
                """ + PLAN_EVAL_FROM + where + " GROUP BY te.risk_level ORDER BY riskLevel", params.toArray());
        List<Map<String, Object>> resultDistribution = jdbcTemplate.queryForList("""
                SELECT COALESCE(te.result_status, 'UNKNOWN') AS resultStatus, COUNT(1) AS evaluationCount
                """ + PLAN_EVAL_FROM + where + " GROUP BY te.result_status ORDER BY resultStatus", params.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", month);
        result.put("orgId", orgId);
        result.put("planCount", summary.get("planCount"));
        result.put("evaluationCount", summary.get("evaluationCount"));
        result.put("avgAchievementRatio", summary.get("avgAchievementRatio"));
        result.put("riskDistribution", riskDistribution);
        result.put("resultDistribution", resultDistribution);
        return result;
    }

    @Override
    public Map<String, Object> orgAchievement(String customerNo) {
        Scope scope = currentScope();
        if (StrUtil.isBlank(customerNo)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "customerNo 必填");
        }
        // 客户所属机构:对公/对私主数据最新批次的开户机构
        String orgCode;
        String orgName;
        List<Map<String, Object>> corp = jdbcTemplate.queryForList("""
                SELECT openact_org_no, openact_org_nm FROM caps_corp_cust_basic_info
                WHERE cust_no = ? AND data_dt = (SELECT MAX(data_dt) FROM caps_corp_cust_basic_info)
                LIMIT 1""", customerNo);
        if (!corp.isEmpty()) {
            orgCode = toStr(corp.get(0).get("openact_org_no"));
            orgName = toStr(corp.get(0).get("openact_org_nm"));
        } else {
            List<Map<String, Object>> indv = jdbcTemplate.queryForList("""
                    SELECT opnact_org_no, opnact_org_nm FROM caps_indv_cust_basic_info
                    WHERE cust_no = ? AND data_dt = (SELECT MAX(data_dt) FROM caps_indv_cust_basic_info)
                    LIMIT 1""", customerNo);
            if (indv.isEmpty()) {
                throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "客户主数据不存在: " + customerNo);
            }
            orgCode = toStr(indv.get(0).get("opnact_org_no"));
            orgName = toStr(indv.get(0).get("opnact_org_nm"));
        }
        if (StrUtil.isBlank(orgCode)) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "客户未登记开户机构: " + customerNo);
        }

        // 机构达成快照(dw_org_performance_snapshot 最新批次最新月份,D19)
        List<Map<String, Object>> perf = jdbcTemplate.queryForList("""
                SELECT data_dt, stat_month, achieved_amount, expected_amount, completion_rate
                FROM dw_org_performance_snapshot
                WHERE org_code = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_org_performance_snapshot)
                ORDER BY stat_month DESC LIMIT 1""", orgCode);
        Map<String, Object> snapshot = perf.isEmpty() ? null : perf.get(0);

        // 本系统评估数据:机构下承诺计划(dept_code ↔ org_code 映射)
        List<Object> orgParams = new ArrayList<>();
        orgParams.add(orgCode);
        Map<String, Object> orgPlanStats = jdbcTemplate.queryForMap("""
                SELECT COUNT(DISTINCT cp.id) AS planCount, AVG(te.achievement_ratio) AS avgAchievementRatio
                FROM ccr_commitment_plan cp
                LEFT JOIN ccr_tracking_evaluation te ON te.plan_id = cp.id AND te.del_flag = '0'
                LEFT JOIN ccr_resolution r ON r.id = cp.resolution_id
                LEFT JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id
                LEFT JOIN ccr_application a ON a.id = pi.application_id
                WHERE cp.del_flag = '0'
                  AND cp.org_id IN (SELECT id FROM ccr_sys_dept WHERE dept_code = ? AND del_flag = '0')
                  AND """ + scopeCondition(scope, orgParams), orgParams.toArray());

        // 该客户自身的承诺计划评估汇总(集团客户含成员口径)
        List<Object> custParams = new ArrayList<>();
        custParams.add(customerNo);
        custParams.add(customerNo);
        Map<String, Object> customerPlanStats = jdbcTemplate.queryForMap("""
                SELECT COUNT(DISTINCT cp.id) AS planCount, AVG(te.achievement_ratio) AS avgAchievementRatio
                FROM ccr_commitment_plan cp
                LEFT JOIN ccr_tracking_evaluation te ON te.plan_id = cp.id AND te.del_flag = '0'
                LEFT JOIN ccr_resolution r ON r.id = cp.resolution_id
                LEFT JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id
                LEFT JOIN ccr_application a ON a.id = pi.application_id
                WHERE cp.del_flag = '0' AND (cp.customer_no = ? OR cp.member_customer_no = ?)
                  AND """ + scopeCondition(scope, custParams), custParams.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("customerNo", customerNo);
        result.put("orgCode", orgCode);
        result.put("orgName", orgName);
        result.put("dataDt", snapshot == null ? null : snapshot.get("data_dt"));
        result.put("statMonth", snapshot == null ? null : snapshot.get("stat_month"));
        result.put("achievedAmount", snapshot == null ? null : snapshot.get("achieved_amount"));
        result.put("expectedAmount", snapshot == null ? null : snapshot.get("expected_amount"));
        result.put("completionRate", snapshot == null ? null : snapshot.get("completion_rate"));
        result.put("orgPlanStats", orgPlanStats);
        result.put("customerPlanStats", customerPlanStats);
        return result;
    }

    private static String toStr(Object value) {
        return value == null ? null : value.toString();
    }
}
