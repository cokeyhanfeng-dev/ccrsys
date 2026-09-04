package com.ccr.common.core.util;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 支行性质支持(2026-09-04 综合/零售两级支行):
 * 支行分综合支行/零售支行两种,零售支行 parent_id 挂到其管理综合支行下(机构表 ccr_sys_dept.branch_type)。
 * 逻辑一律走 parent_id 组织树,不依赖机构编码嵌套。供路由(retailBranch 注入)/审批人解析/待办可见共用。
 */
public final class BranchTypeSupport {

    /** 零售支行性质码 */
    public static final String RETAIL = "RETAIL";

    private BranchTypeSupport() {
    }

    /**
     * 是否零售支行(org_type='BRANCH' AND branch_type='RETAIL')。
     * orgId 空/机构不存在/非零售支行一律 false;综合支行=空或 COMPREHENSIVE,不进此判。
     */
    public static boolean isRetailBranch(JdbcTemplate jdbcTemplate, Long orgId) {
        if (jdbcTemplate == null || orgId == null) {
            return false;
        }
        List<Long> hit = jdbcTemplate.queryForList(
                "SELECT id FROM ccr_sys_dept WHERE del_flag = '0' AND id = ?"
                        + " AND org_type = 'BRANCH' AND branch_type = '" + RETAIL + "' LIMIT 1",
                Long.class, orgId);
        return !hit.isEmpty();
    }

    /**
     * 零售支行的管理综合支行机构 org_code:申请人机构须为零售支行,沿 parent_id 取上级,
     * 上级为 BRANCH 机构即返回其 org_code(create 校验保证管理行非零售)。否则返回 null(数据未配,安全失败)。
     */
    public static String managingComprehensiveBranchCode(JdbcTemplate jdbcTemplate, Long orgId) {
        if (jdbcTemplate == null || orgId == null) {
            return null;
        }
        List<String> codes = jdbcTemplate.queryForList(
                "SELECT p.org_code FROM ccr_sys_dept org"
                        + " JOIN ccr_sys_dept p ON p.id = org.parent_id AND p.del_flag = '0'"
                        + " WHERE org.id = ? AND org.del_flag = '0'"
                        + "   AND org.org_type = 'BRANCH' AND org.branch_type = '" + RETAIL + "'"
                        + "   AND p.org_type = 'BRANCH'",
                String.class, orgId);
        return codes.isEmpty() ? null : codes.get(0);
    }

    /** 给定综合支行机构 id,取其直接下级零售支行机构 id 列表(综合支行长待办/详情可见范围) */
    public static List<Long> directRetailChildIds(JdbcTemplate jdbcTemplate, Long orgId) {
        if (jdbcTemplate == null || orgId == null) {
            return List.of();
        }
        return jdbcTemplate.queryForList(
                "SELECT id FROM ccr_sys_dept WHERE del_flag = '0' AND parent_id = ?"
                        + " AND org_type = 'BRANCH' AND branch_type = '" + RETAIL + "'",
                Long.class, orgId);
    }
}
