package com.ccr.common.core.util;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 机构达成实时组装(增量021 B方案):废弃数仓机构达成表 dw_org_performance_snapshot,
 * 机构达成率由本系统按「申请机构 + 承诺指标 + 数仓客户贡献度」实时加工——数仓无法得知
 * "哪个申请是哪个机构申请的"(expected_amount 需要申请-机构归属,数仓算不了)。
 * 口径:
 * - 机构定位:ccr_application.applicant_org_id → ccr_sys_dept.org_code(申请机构)
 * - 分母 expectedAmount = 该机构已通过审批申请(承诺计划)指标 target_value 求和(metric_code != 'OTHER')
 * - 分子 achievedAmount = 该机构承诺计划客户集合(单户 customer_no + 集团成员 member_customer_no)
 *   的 dw_contribution_metric TOTAL 行(CONTRIBUTION_AMOUNT 优先)最新批次值求和
 * - completionRate = achieved / expected;statMonth = 当月;dataDt = 最新贡献度批次
 * 被审批详情/档案(orgPerformance)与承诺概览(orgAchievement)复用;字段对齐原数仓快照。
 */
public final class OrgAchievementAssembler {

    private OrgAchievementAssembler() {
    }

    /** 返回 0/1 条;字段:orgCode/orgName/statMonth/achievedAmount/expectedAmount/completionRate/dataDt */
    public static List<Map<String, Object>> assemble(JdbcTemplate jdbcTemplate, String orgCode) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (orgCode == null || orgCode.isBlank()) {
            return result;
        }
        // 分母:该机构(申请机构)已通过审批申请(承诺计划)指标 target_value 求和(顺带取机构名称)
        List<Map<String, Object>> denomRows = jdbcTemplate.queryForList(
                "SELECT d.dept_name orgName, COALESCE(SUM(m.target_value), 0) expectedAmount "
                        + "FROM ccr_commitment_plan cp "
                        + "JOIN ccr_resolution r ON r.id = cp.resolution_id "
                        + "JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id "
                        + "JOIN ccr_application a ON a.id = pi.application_id "
                        + "JOIN ccr_sys_dept d ON d.id = a.applicant_org_id "
                        + "LEFT JOIN ccr_commitment_metric m ON m.plan_id = cp.id AND m.del_flag = '0' "
                        + "WHERE cp.del_flag = '0' AND d.del_flag = '0' AND d.org_code = ? "
                        + "AND m.metric_code != 'OTHER'", orgCode);
        BigDecimal expected = toBigDecimal(denomRows.get(0).get("expectedAmount"));
        if (expected == null || expected.compareTo(BigDecimal.ZERO) == 0) {
            // 该机构无承诺计划 → 与原数仓表空行行为一致,返回空列表
            return result;
        }
        // 客户集合:该机构承诺计划客户
        List<Map<String, Object>> custRows = jdbcTemplate.queryForList(
                "SELECT cp.customer_no customerNo, cp.member_customer_no memberCustomerNo "
                        + "FROM ccr_commitment_plan cp "
                        + "JOIN ccr_resolution r ON r.id = cp.resolution_id "
                        + "JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id "
                        + "JOIN ccr_application a ON a.id = pi.application_id "
                        + "JOIN ccr_sys_dept d ON d.id = a.applicant_org_id "
                        + "WHERE cp.del_flag = '0' AND d.del_flag = '0' AND d.org_code = ?", orgCode);
        Set<String> customers = new LinkedHashSet<>();
        for (Map<String, Object> row : custRows) {
            addIfNotBlank(customers, row.get("customerNo"));
            addIfNotBlank(customers, row.get("memberCustomerNo"));
        }
        // 分子:客户集合 TOTAL 行(贡献度主口径,CONTRIBUTION_AMOUNT 行优先,最新批次)求和
        BigDecimal achieved = BigDecimal.ZERO;
        String dataDt = null;
        if (!customers.isEmpty()) {
            Map<String, List<Map<String, Object>>> byKey = new LinkedHashMap<>();
            jdbcTemplate.queryForList(
                            "SELECT cust_no custNo, metric_value metricValue, value_type valueType, data_dt "
                                    + "FROM dw_contribution_metric WHERE cust_no IN (" + inClause(customers) + ") "
                                    + "AND metric_code = 'TOTAL'")
                    .forEach(row -> byKey
                            .computeIfAbsent(String.valueOf(row.get("custNo")), k -> new ArrayList<>()).add(row));
            for (Map.Entry<String, List<Map<String, Object>>> e : byKey.entrySet()) {
                Map<String, Object> chosen = pickLatest(e.getValue());
                if (chosen != null && chosen.get("metricValue") != null) {
                    achieved = achieved.add(new BigDecimal(chosen.get("metricValue").toString()));
                    Object dt = chosen.get("data_dt");
                    if (dt != null && (dataDt == null || dt.toString().compareTo(dataDt) > 0)) {
                        dataDt = dt.toString();
                    }
                }
            }
        }
        BigDecimal completionRate = achieved.divide(expected, 4, RoundingMode.HALF_UP);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("orgCode", orgCode);
        row.put("orgName", denomRows.get(0).get("orgName"));
        row.put("statMonth", LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM")));
        row.put("achievedAmount", achieved);
        row.put("expectedAmount", expected);
        row.put("completionRate", completionRate);
        row.put("dataDt", dataDt);
        result.add(row);
        return result;
    }

    /** 取一客户 TOTAL 行:CONTRIBUTION_AMOUNT 行优先,否则最新 data_dt 行 */
    private static Map<String, Object> pickLatest(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            if ("CONTRIBUTION_AMOUNT".equals(String.valueOf(row.get("valueType")))) {
                return row;
            }
        }
        Map<String, Object> chosen = null;
        String maxDt = null;
        for (Map<String, Object> row : rows) {
            Object dt = row.get("data_dt");
            if (dt != null && (maxDt == null || dt.toString().compareTo(maxDt) > 0)) {
                maxDt = dt.toString();
                chosen = row;
            }
        }
        return chosen;
    }

    private static void addIfNotBlank(Set<String> set, Object value) {
        if (value != null && !value.toString().isBlank()) {
            set.add(value.toString());
        }
    }

    private static BigDecimal toBigDecimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    /** IN 子句(单引号转义防注入;值来自数仓客户号,与 ContributionMerger 同款) */
    private static String inClause(Collection<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append("'").append(v.replace("'", "''")).append("'");
        }
        return sb.toString();
    }
}
