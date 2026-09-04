package com.ccr.common.core.util;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 机构达成组装(2026-09-04 两版承诺计划合并改造):废弃旧承诺表金额口径(dw_contribution_metric TOTAL 贡献度求和
 * 作分子 / ccr_commitment_plan target_value 求和作分母),统一改从 v2 单表 ccr_commitment_track 聚合到期终态,
 * 与承诺跟踪页机构达成率(CommitmentTrackServiceImpl.orgAchievement)同一口径:
 * 达成率 = 到期已定案承诺中 FINISHED_MET 占比 = SUM(status='FINISHED_MET') / COUNT(*)。
 * 机构定位:org_id = 申请机构 ccr_sys_dept.id(与 v2 承诺跟踪 org_id 同域),detail/archive 直传 applicant_org_id。
 * 用途:审批详情/档案机构达成卡(前端只展示达成率 + 进度条)与档案导出(HistoryArchiveExporter)。
 * 存贷比(RATIO)等比例型承诺到期结算后同样计为一条达成,无金额污染问题(口径为条数非金额)。
 */
public final class OrgAchievementAssembler {

    private OrgAchievementAssembler() {
    }

    /**
     * 按机构聚合 ccr_commitment_track 到期终态(FINISHED_MET/FINISHED_UNMET),返回 0/1 行。
     * 字段:orgId/orgCode/orgName/finishedTotal(到期承诺项数)/metCount(已达成项数)
     * /completionRate(达成率 0-1)/completionRatePct(达成率 ×100,导出用)。
     * 无该机构终态行 → 空列表(detail 卡暂无数据,archive 卡不渲染)。
     */
    public static List<Map<String, Object>> assemble(JdbcTemplate jdbcTemplate, Long orgId) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (orgId == null) {
            return result;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT t.org_id orgId, d.org_code orgCode, d.dept_name orgName,"
                        + " COUNT(*) finishedTotal,"
                        + " SUM(t.status = 'FINISHED_MET') metCount,"
                        + " ROUND(SUM(t.status = 'FINISHED_MET') / COUNT(*), 4) completionRate"
                        + " FROM ccr_commitment_track t"
                        + " JOIN ccr_sys_dept d ON d.id = t.org_id AND d.del_flag = '0'"
                        + " WHERE t.org_id = ? AND t.del_flag = '0'"
                        + "   AND t.status IN ('FINISHED_MET','FINISHED_UNMET')"
                        + " GROUP BY t.org_id, d.org_code, d.dept_name", orgId);
        if (rows.isEmpty()) {
            return result;
        }
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        Object rate = row.get("completionRate");
        if (rate instanceof BigDecimal rateBd) {
            row.put("completionRatePct", rateBd.multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP));
        }
        result.add(row);
        return result;
    }
}
