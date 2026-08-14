package com.ccr.common.core.assignee;

import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点审批人解析器(§5.5.1/§10.3.19)
 * 四层解析,命中即止:PERSON(按工号直接命中) → GROUP(角色集合) → DEPT(机构org_code前缀匹配
 * 该机构下启用用户,且仅在申请人机构归属于配置机构时生效) → ROLE(角色兜底)。
 * delegate_to 在代理有效期内替换原处理人;配置有效期(valid_from/valid_to)外不参与解析。
 * 空结果语义:节点无任何有效配置(或配置未解析出启用用户)时返回空列表 = 不限制,
 * 调用方保持现有角色匹配；配置查询失败返回 ERROR，业务入口拒绝按角色兜底。
 */
@Slf4j
@Component
public class NodeAssigneeResolver {

    /** 命中层级:无配置/未解析出处理人(=不限制) */
    public static final String LEVEL_NONE = "NONE";

    /** 指派配置解析失败；业务操作与对象授权必须拒绝继续执行。 */
    public static final String LEVEL_ERROR = "ERROR";

    /** 命中层级:部门-分管行长映射(§D16a,一人可分管多部门) */
    public static final String LEVEL_DEPT_VP = "DEPT_VP";

    /** 分管行领导节点编码(部门-分管行长映射专用;ccr-common 不依赖 ccr-approval,字符串字面量) */
    private static final String VICE_PRESIDENT_NODE = "VICE_PRESIDENT";

    private static final List<String> LAYER_ORDER = List.of("PERSON", "GROUP", "DEPT", "ROLE");

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 解析节点实际处理人(仅全局配置,即 flow_key 为空的指派) */
    public ResolveResult resolve(String nodeCode, Long orgId) {
        return resolve(nodeCode, orgId, null, null);
    }

    /** 解析节点实际处理人(带流程定义key) */
    public ResolveResult resolve(String nodeCode, Long orgId, String flowKey) {
        return resolve(nodeCode, orgId, flowKey, null);
    }

    /**
     * 解析节点实际处理人
     *
     * @param nodeCode 节点编码
     * @param orgId    申请人机构(ccr_sys_dept.id),用于 DEPT 层归属判定;可空(空时 DEPT 配置不参与)
     * @param flowKey  流程定义key,可空;非空时同时命中该流程专属配置与全局配置
     * @param deptCode 分项部门归属编码(§D16a,矩阵透出落库:机构org_code——3202233912公司金融部/3202233943授信评审部/3202233991零售金融;2026-08-14 统一 org_code);部门类节点
     *                 (DEPT_GENERAL_MANAGER/VICE_PRESIDENT)按此解析,非部门节点传 null 走原逻辑
     */
    public ResolveResult resolve(String nodeCode, Long orgId, String flowKey, String deptCode) {
        try {
            // §D16a 分管行领导:分项 dept_code 已冻结时按部门-分管行长映射解析(一人可分管多部门,纯配置)
            if (VICE_PRESIDENT_NODE.equals(nodeCode) && deptCode != null && !deptCode.isBlank()) {
                List<AssigneeUser> vp = findDeptVpUsers(deptCode);
                if (!vp.isEmpty()) {
                    return new ResolveResult(nodeCode, LEVEL_DEPT_VP, vp);
                }
            }
            List<Map<String, Object>> configs = loadConfigs(nodeCode, flowKey);
            if (configs.isEmpty()) {
                return ResolveResult.empty(nodeCode);
            }
            String applicantOrgCode = orgId == null ? null : orgCodeOf(orgId);
            for (String layer : LAYER_ORDER) {
                List<Map<String, Object>> layerConfigs = configs.stream()
                        .filter(c -> layer.equals(c.get("assignee_type"))).toList();
                if (layerConfigs.isEmpty()) {
                    continue;
                }
                List<AssigneeUser> users = resolveLayer(layer, layerConfigs, applicantOrgCode, deptCode);
                if (!users.isEmpty()) {
                    return new ResolveResult(nodeCode, layer, users);
                }
            }
            return ResolveResult.empty(nodeCode);
        } catch (DataAccessException e) {
            log.error("节点审批人配置解析失败,拒绝按角色兜底: nodeCode={}, 原因={}", nodeCode, e.getMessage());
            return ResolveResult.failed(nodeCode);
        }
    }

    /** 解析节点处理人用户id列表(空=不限制) */
    public List<Long> resolveUserIds(String nodeCode, Long orgId) {
        return requireResolved(resolve(nodeCode, orgId));
    }

    /** 带分项部门归属编码的解析(§D16a:部门总经理/分管行长按分项 dept_code 解析处理人) */
    public List<Long> resolveUserIds(String nodeCode, Long orgId, String deptCode) {
        return requireResolved(resolve(nodeCode, orgId, null, deptCode));
    }

    private List<Long> requireResolved(ResolveResult result) {
        if (LEVEL_ERROR.equals(result.getHitLevel())) {
            throw new ServiceException(ErrorCode.INTERNAL_ERROR.getCode(),
                    "节点审批人配置暂不可用,请稍后重试");
        }
        return result.userIds();
    }

    // ---------- 私有 ----------

    /** 读取节点当前有效配置(status=ACTIVE 且在配置有效期内;flow_key 命中全局或指定流程) */
    private List<Map<String, Object>> loadConfigs(String nodeCode, String flowKey) {
        LocalDate today = LocalDate.now();
        String sql = """
                SELECT id, assignee_type, assignee_code, relation,
                       delegate_to, delegate_valid_from, delegate_valid_to
                FROM ccr_node_assignee
                WHERE node_code = ? AND status = 'ACTIVE' AND del_flag = '0'
                  AND (valid_from IS NULL OR valid_from <= ?)
                  AND (valid_to IS NULL OR valid_to >= ?)
                  AND (flow_key IS NULL OR flow_key = '' OR flow_key = ?)
                ORDER BY id
                """;
        return jdbcTemplate.queryForList(sql, nodeCode, today, today, flowKey == null ? "" : flowKey);
    }

    /** 单层解析:按指派类型展开为启用用户列表(去重,保持配置顺序) */
    private List<AssigneeUser> resolveLayer(String layer, List<Map<String, Object>> layerConfigs,
                                            String applicantOrgCode, String deptCode) {
        Map<Long, AssigneeUser> users = new LinkedHashMap<>();
        for (Map<String, Object> config : layerConfigs) {
            String assigneeCode = String.valueOf(config.get("assignee_code"));
            switch (layer) {
                case "PERSON" -> {
                    AssigneeUser user = findEnabledUserByUsername(assigneeCode);
                    if (user != null) {
                        user = applyDelegate(user, config);
                        users.putIfAbsent(user.getUserId(), user);
                    }
                }
                case "GROUP" -> {
                    // 组编码=角色集合(逗号分隔角色码)
                    for (String roleCode : assigneeCode.split(",")) {
                        for (AssigneeUser user : findEnabledUsersByRole(roleCode.trim())) {
                            users.putIfAbsent(user.getUserId(), user);
                        }
                    }
                }
                case "DEPT" -> {
                    // 「dept_code:role」语法(§D16a 部门分流):按分项部门归属编码精确匹配机构 org_code,
                    // 取该机构下指定角色启用用户(部门总经理按所属部门解析,不依赖具体工号;2026-08-14 统一 org_code)
                    int colon = assigneeCode.indexOf(':');
                    if (colon > 0) {
                        String cfgDeptCode = assigneeCode.substring(0, colon);
                        String roleCode = assigneeCode.substring(colon + 1);
                        if (deptCode != null && deptCode.equals(cfgDeptCode)) {
                            for (AssigneeUser user : findEnabledUsersByRoleAndDept(roleCode, cfgDeptCode)) {
                                users.putIfAbsent(user.getUserId(), user);
                            }
                        }
                    } else {
                        // 原语义:申请人机构归属于配置机构(org_code 前缀匹配)时,该机构及下级机构启用用户入选
                        if (applicantOrgCode != null && applicantOrgCode.startsWith(assigneeCode)) {
                            for (AssigneeUser user : findEnabledUsersUnderOrg(assigneeCode)) {
                                users.putIfAbsent(user.getUserId(), user);
                            }
                        }
                    }
                }
                case "ROLE" -> {
                    for (AssigneeUser user : findEnabledUsersByRole(assigneeCode)) {
                        users.putIfAbsent(user.getUserId(), user);
                    }
                }
                default -> log.warn("未知指派类型: {}", layer);
            }
        }
        return new ArrayList<>(users.values());
    }

    /** 代理暂代:delegate_to 在代理有效期内替换原处理人(空有效期边界=该侧不限) */
    private AssigneeUser applyDelegate(AssigneeUser original, Map<String, Object> config) {
        Object delegateTo = config.get("delegate_to");
        if (delegateTo == null || delegateTo.toString().isBlank()) {
            return original;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = toDateTime(config.get("delegate_valid_from"));
        LocalDateTime to = toDateTime(config.get("delegate_valid_to"));
        if ((from != null && now.isBefore(from)) || (to != null && now.isAfter(to))) {
            return original;
        }
        AssigneeUser delegate = findEnabledUserByUsername(delegateTo.toString());
        if (delegate == null) {
            return original;
        }
        delegate.setDelegatedFrom(original.getUsername());
        return delegate;
    }

    private AssigneeUser findEnabledUserByUsername(String username) {
        List<AssigneeUser> users = jdbcTemplate.query("""
                        SELECT id, username, nick_name FROM ccr_sys_user
                        WHERE username = ? AND status = 'ENABLE' AND del_flag = '0'
                        """,
                (rs, i) -> new AssigneeUser(rs.getLong("id"), rs.getString("username"),
                        rs.getString("nick_name")), username);
        return users.isEmpty() ? null : users.get(0);
    }

    private List<AssigneeUser> findEnabledUsersByRole(String roleCode) {
        return jdbcTemplate.query("""
                        SELECT id, username, nick_name FROM ccr_sys_user
                        WHERE role_code = ? AND status = 'ENABLE' AND del_flag = '0'
                        ORDER BY id
                        """,
                (rs, i) -> new AssigneeUser(rs.getLong("id"), rs.getString("username"),
                        rs.getString("nick_name")), roleCode);
    }

    /** 机构及下级机构(org_code 前缀匹配)下全部启用用户 */
    private List<AssigneeUser> findEnabledUsersUnderOrg(String orgCode) {
        return jdbcTemplate.query("""
                        SELECT u.id, u.username, u.nick_name FROM ccr_sys_user u
                        JOIN ccr_sys_dept d ON d.id = u.org_id AND d.del_flag = '0'
                        WHERE d.org_code LIKE CONCAT(?, '%')
                          AND u.status = 'ENABLE' AND u.del_flag = '0'
                        ORDER BY u.id
                        """,
                (rs, i) -> new AssigneeUser(rs.getLong("id"), rs.getString("username"),
                        rs.getString("nick_name")), orgCode);
    }

    /** 指定部门(org_code 精确匹配机构)下指定角色启用用户(§D16a 部门总经理按部门归属解析;2026-08-14 统一 org_code) */
    private List<AssigneeUser> findEnabledUsersByRoleAndDept(String roleCode, String deptCode) {
        return jdbcTemplate.query("""
                        SELECT u.id, u.username, u.nick_name FROM ccr_sys_user u
                        JOIN ccr_sys_dept d ON d.id = u.org_id AND d.del_flag = '0'
                        WHERE d.org_code = ? AND u.role_code = ?
                          AND u.status = 'ENABLE' AND u.del_flag = '0'
                        ORDER BY u.id
                        """,
                (rs, i) -> new AssigneeUser(rs.getLong("id"), rs.getString("username"),
                        rs.getString("nick_name")), deptCode, roleCode);
    }

    /** 部门-分管行长映射(§D16a):按分项部门归属编码查分管行领导,一人可分管多部门(纯配置) */
    private List<AssigneeUser> findDeptVpUsers(String deptCode) {
        LocalDateTime now = LocalDateTime.now();
        return jdbcTemplate.query("""
                        SELECT u.id, u.username, u.nick_name FROM ccr_dept_vp vp
                        JOIN ccr_sys_user u ON u.id = vp.vp_user_id AND u.status = 'ENABLE' AND u.del_flag = '0'
                        WHERE vp.dept_code = ? AND vp.status = 'ACTIVE' AND vp.del_flag = '0'
                          AND (vp.valid_from IS NULL OR vp.valid_from <= ?)
                          AND (vp.valid_to IS NULL OR vp.valid_to >= ?)
                        ORDER BY vp.id
                        """,
                (rs, i) -> new AssigneeUser(rs.getLong("id"), rs.getString("username"),
                        rs.getString("nick_name")), deptCode, now, now);
    }

    /** 机构id → org_code(查不到返回 null,DEPT 层不命中) */
    private String orgCodeOf(Long orgId) {
        List<String> codes = jdbcTemplate.queryForList(
                "SELECT org_code FROM ccr_sys_dept WHERE id = ? AND del_flag = '0'", String.class, orgId);
        return codes.isEmpty() ? null : codes.get(0);
    }

    private LocalDateTime toDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return LocalDateTime.parse(value.toString().replace(' ', 'T'));
    }

    /** 解析结果:命中层级 + 实际处理人列表(空列表=不限制,走角色兜底) */
    @Data
    public static class ResolveResult {
        private final String nodeCode;
        /** 命中层级:PERSON/GROUP/DEPT/ROLE;NONE=未配置或未解析出处理人;ERROR=解析失败 */
        private final String hitLevel;
        private final List<AssigneeUser> users;

        static ResolveResult empty(String nodeCode) {
            return new ResolveResult(nodeCode, LEVEL_NONE, List.of());
        }

        static ResolveResult failed(String nodeCode) {
            return new ResolveResult(nodeCode, LEVEL_ERROR, List.of());
        }

        public List<Long> userIds() {
            return users.stream().map(AssigneeUser::getUserId).toList();
        }
    }

    /** 解析出的处理人 */
    @Data
    public static class AssigneeUser {
        private final Long userId;
        private final String username;
        private final String nickName;
        /** 代理暂代:原处理人工号(非空表示本条为代理人替换) */
        private String delegatedFrom;

        AssigneeUser(Long userId, String username, String nickName) {
            this.userId = userId;
            this.username = username;
            this.nickName = nickName;
        }
    }
}
