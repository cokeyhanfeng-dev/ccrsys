package com.ccr.common.core.util;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 贡献度展示口径工具:收敛到启用指标字典 + 关联人归并(展示层聚合)。
 * ①收敛:贡献度展示只保留 ccr_metric_definition status=ACTIVE 的指标(恰好 8 个对公指标),
 *   历史/停用码(如 DAILY_DEPOSIT、TOTAL、GM_*、对私等)不展示。
 * ②归并:把关联人同 metric_code 的最新数仓值加总进主客户贡献度行——
 *   "关联人的贡献度也算给申请客户"(用户确认)。
 * 归并取数规则沿用承诺跟踪 relatedSummary 的"同码才并、最新批次、折算(CONTRIBUTION_AMOUNT)行优先";
 * RATIO 派生指标(存贷款比)不归并;无关联人/无同码数据时原样返回(空值保持空)。
 * 本工具被审批详情/档案/申请页贡献度展示与承诺基线回填复用。
 */
public final class ContributionMerger {

    private ContributionMerger() {
    }

    /**
     * 收敛并归并贡献度(原地修改):先按启用指标字典过滤,再把关联人同码值加总进主客户贡献度行。
     *
     * @param contribution       主客户贡献度行列表,每行含 camelCase 字段 metricCode/metricValue/valueType
     * @param relatedCustomerNos 关联人客户号(自动去重;空集合则仅收敛不归并)
     */
    public static void mergeRelatedContributions(JdbcTemplate jdbcTemplate,
                                                 List<Map<String, Object>> contribution,
                                                 Collection<String> relatedCustomerNos) {
        if (contribution == null || contribution.isEmpty()) {
            return;
        }
        // ① 收敛到启用指标字典(恰好 8 个对公指标):贡献度展示只保留启用指标,历史/停用码不展示;
        //    同时用字典权威 metric_name 覆盖数仓旧名(如"存款日均"→"存款年日均")
        List<Map<String, Object>> activeDefs = jdbcTemplate.queryForList(
                "SELECT metric_code, metric_name FROM ccr_metric_definition WHERE status = 'ACTIVE' AND del_flag = '0'");
        Map<String, String> nameByCode = new HashMap<>();
        for (Map<String, Object> def : activeDefs) {
            Object code = def.get("metric_code");
            Object name = def.get("metric_name");
            if (code != null && name != null) {
                nameByCode.put(code.toString(), name.toString());
            }
        }
        if (!nameByCode.isEmpty()) {
            contribution.removeIf(row -> row.get("metricCode") == null
                    || !nameByCode.containsKey(row.get("metricCode").toString()));
            for (Map<String, Object> row : contribution) {
                String name = nameByCode.get(row.get("metricCode").toString());
                if (name != null) {
                    row.put("metricName", name);
                }
            }
        }
        if (contribution.isEmpty() || relatedCustomerNos == null || relatedCustomerNos.isEmpty()) {
            return;
        }
        Set<String> relatedNos = new LinkedHashSet<>();
        for (String no : relatedCustomerNos) {
            if (no != null && !no.isBlank()) {
                relatedNos.add(no);
            }
        }
        if (relatedNos.isEmpty()) {
            return;
        }
        // 收集主客户贡献度行指标码(RATIO 派生指标不归并)
        Set<String> metricCodes = new LinkedHashSet<>();
        for (Map<String, Object> row : contribution) {
            if ("RATIO".equals(row.get("valueType"))) {
                continue;
            }
            Object code = row.get("metricCode");
            if (code != null && !code.toString().isBlank()) {
                metricCodes.add(code.toString());
            }
        }
        if (metricCodes.isEmpty()) {
            return;
        }
        // 批查关联人数仓贡献度:每组 (cust_no, metric_code) 取最近批次,折算(CONTRIBUTION_AMOUNT)行优先
        Map<String, List<Map<String, Object>>> byKey = new HashMap<>();
        jdbcTemplate.queryForList(
                        "SELECT cust_no custNo, metric_code metricCode, metric_value metricValue, value_type valueType, data_dt "
                                + "FROM dw_contribution_metric WHERE cust_no IN (" + inClause(relatedNos) + ") "
                                + "AND metric_code IN (" + inClause(metricCodes) + ")")
                .forEach(row -> {
                    String key = keyOf(String.valueOf(row.get("custNo")), String.valueOf(row.get("metricCode")));
                    byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
                });
        Map<String, BigDecimal> latestByKey = new HashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byKey.entrySet()) {
            Map<String, Object> chosen = null;
            for (Map<String, Object> row : e.getValue()) {
                if ("CONTRIBUTION_AMOUNT".equals(String.valueOf(row.get("valueType")))) {
                    chosen = row;
                    break;
                }
            }
            if (chosen == null) {
                String maxDt = null;
                for (Map<String, Object> row : e.getValue()) {
                    Object dt = row.get("data_dt");
                    if (dt != null && (maxDt == null || dt.toString().compareTo(maxDt) > 0)) {
                        maxDt = dt.toString();
                    }
                }
                for (Map<String, Object> row : e.getValue()) {
                    if (maxDt != null && maxDt.equals(String.valueOf(row.get("data_dt")))) {
                        chosen = row;
                        break;
                    }
                }
            }
            if (chosen != null && chosen.get("metricValue") != null) {
                latestByKey.put(e.getKey(), new BigDecimal(chosen.get("metricValue").toString()));
            }
        }
        if (latestByKey.isEmpty()) {
            return;
        }
        // 对每行:主客户值 + 关联人合计(同码才并)
        for (Map<String, Object> row : contribution) {
            if ("RATIO".equals(row.get("valueType"))) {
                continue;
            }
            String code = row.get("metricCode") == null ? null : row.get("metricCode").toString();
            if (code == null) {
                continue;
            }
            BigDecimal relatedSum = BigDecimal.ZERO;
            boolean hasRelated = false;
            for (String relNo : relatedNos) {
                BigDecimal value = latestByKey.get(keyOf(relNo, code));
                if (value != null) {
                    relatedSum = relatedSum.add(value);
                    hasRelated = true;
                }
            }
            if (!hasRelated) {
                continue;
            }
            Object mainValue = row.get("metricValue");
            BigDecimal main = mainValue == null ? BigDecimal.ZERO : new BigDecimal(mainValue.toString());
            row.put("metricValue", main.add(relatedSum));
            row.put("relatedIncluded", true);
        }
    }

    private static String keyOf(String custNo, String metricCode) {
        return custNo + '|' + metricCode;
    }

    /** IN 子句(单引号转义防注入;值来自数仓客户号/字典码,与承诺跟踪 inClause 同款) */
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
