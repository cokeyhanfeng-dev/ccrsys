package com.ccr.common.core.assignee;

import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import com.ccr.common.core.util.BranchTypeSupport;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** 支行行长节点编码(2026-08-14 真实支行行长数据落地:按申请人机构解析该支行 branch_manager 用户) */
    private static final String BRANCH_MANAGER_NODE = "BRANCH_MANAGER";
    private static final String BRANCH_MANAGER_ROLE = "branch_manager";

    /** 秘书岗节点编码(需求四:贷审会秘书,2026-08-14 改由计划财务部总经理兼任;固定机构+角色解析,与分项部门归属无关) */
    private static final String SECRETARY_NODE = "SECRETARY";

    /** 管理综合支行长节点编码(2026-09-04 综合/零售两级支行:按申请人机构组织树确定性解析,不依赖指派配置) */
    private static final String PARENT_BRANCH_MANAGER_NODE = "PARENT_BRANCH_MANAGER";

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
            // §2026-09-04 综合/零售两级支行:管理综合支行长按申请人机构组织树确定性解析(先于指派配置——
            // 不新增 ccr_node_assignee 配置行,语义=零售支行 parent_id 的管理综合支行 branch_manager 用户)
            if (PARENT_BRANCH_MANAGER_NODE.equals(nodeCode)) {
                return resolveParentBranchManager(orgId);
            }
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
                List<AssigneeUser> users = resolveLayer(layer, layerConfigs, orgId, applicantOrgCode, deptCode, nodeCode);
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

    /**
     * 管理综合支行长解析(2026-09-04):申请人机构须为零售支行(BRANCH+RETAIL),沿 parent_id 取管理综合支行
     * (create 校验保证管理行为非零售 BRANCH),解析该综合支行下 branch_manager 角色启用用户;
     * orgId 空 / 非零售支行 / 管理行非 BRANCH / 无启用行长 → 返回空(调用方 guardNodeAssignee 拒绝角色兜底,防越权)。
     */
    private ResolveResult resolveParentBranchManager(Long orgId) {
        String parentOrgCode = BranchTypeSupport.managingComprehensiveBranchCode(jdbcTemplate, orgId);
        if (parentOrgCode == null) {
            return ResolveResult.empty(PARENT_BRANCH_MANAGER_NODE);
        }
        List<AssigneeUser> users = findEnabledUsersByRoleAndDept(BRANCH_MANAGER_ROLE, parentOrgCode);
        return users.isEmpty() ? ResolveResult.empty(PARENT_BRANCH_MANAGER_NODE)
                : new ResolveResult(PARENT_BRANCH_MANAGER_NODE, "DEPT", users);
    }

    /** 解析节点处理人用户id列表(空=不限制) */
    public List<Long> resolveUserIds(String nodeCode, Long orgId) {
        return requireResolved(resolve(nodeCode, orgId));
    }

    /** 带分项部门归属编码的解析(§D16a:部门总经理/分管行长按分项 dept_code 解析处理人) */
    public List<Long> resolveUserIds(String nodeCode, Long orgId, String deptCode) {
        return requireResolved(resolve(nodeCode, orgId, null, deptCode));
    }

    /**
     * 判断用户是否在节点配置的指派名单中(兼岗识别,§D-7 六人小组配置化)
     * 用于 role_code 非委员但被配置为小组成员的兼岗用户(如授信评审部总经理兼小组成员)登录角色附加与
     * 替补校验;解析失败或节点未配置按不在名单处理,不阻断主流程。
     */
    public boolean isUserInAssignees(String nodeCode, Long userId) {
        if (userId == null) {
            return false;
        }
        try {
            return resolveUserIds(nodeCode, null).contains(userId);
        } catch (Exception e) {
            log.warn("节点名单解析失败,按非名单成员处理: nodeCode={}, userId={}, 原因={}",
                    nodeCode, userId, e.getMessage());
            return false;
        }
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
    private List<AssigneeUser> resolveLayer(String layer, List<Map<String, Object>> layerConfigs, Long orgId,
                                            String applicantOrgCode, String deptCode, String nodeCode) {
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
                        } else if (SECRETARY_NODE.equals(nodeCode)) {
                            // 固定机构节点(秘书岗=计划财务部总经理兼任):与分项部门归属/申请人机构无关,
                            // 无论分项 dept_code 是否冻结一律按配置机构+角色直接解析(2026-08-27 修复:
                            // 矩阵透出 dept_code 后 SECRETARY 解析恒空 → 秘书岗审批 1007 不具备节点角色);
                            // 部门总经理/分管行长仍走 deptCode 分流,deptCode 为空不命中
                            for (AssigneeUser user : findEnabledUsersByRoleAndDept(roleCode, cfgDeptCode)) {
                                users.putIfAbsent(user.getUserId(), user);
                            }
                        }
                    } else {
                        // 归属判定:申请人机构是否归属于配置机构组织树。org_code 字符串前缀在真实机构码下失效
                        // (支行 3202233050 不以总行 3202230000 开头),2026-08-14 改按 parent_id 组织树归属;
                        // 命中时该机构及下级机构启用用户入选
                        if (belongsToOrgTree(orgId, assigneeCode)) {
                            if (BRANCH_MANAGER_NODE.equals(nodeCode)) {
                                // 支行行长(2026-08-14 真实行长数据落地):按申请人机构(org_code 精确)取该机构下
                                // branch_manager 角色用户——各支行申请自动流到本支行真实行长;配置机构=总行前缀
                                // 3202230000 覆盖全部支行(未配置行长的支行解析为空,走角色兜底)
                                for (AssigneeUser user : findEnabledUsersByRoleAndDept(BRANCH_MANAGER_ROLE, applicantOrgCode)) {
                                    users.putIfAbsent(user.getUserId(), user);
                                }
                            } else {
                                for (AssigneeUser user : findEnabledUsersUnderOrg(assigneeCode)) {
                                    users.putIfAbsent(user.getUserId(), user);
                                }
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

    /** 机构及下级机构(组织树子树)下全部启用用户 */
    private List<AssigneeUser> findEnabledUsersUnderOrg(String orgCode) {
        Set<Long> ids = subtreeOrgIds(orgCode);
        if (ids.isEmpty()) {
            return List.of();
        }
        String inSql = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbcTemplate.query("""
                        SELECT id, username, nick_name FROM ccr_sys_user
                        WHERE org_id IN (""" + inSql + """
                          )
                          AND status = 'ENABLE' AND del_flag = '0'
                        ORDER BY id
                        """,
                (rs, i) -> new AssigneeUser(rs.getLong("id"), rs.getString("username"),
                        rs.getString("nick_name")), ids.toArray());
    }

    /** 组织树归属判定:申请人机构(orgId)是否归属于配置机构(org_code 定位)的子树(含自身) */
    private boolean belongsToOrgTree(Long orgId, String orgCode) {
        return orgId != null && subtreeOrgIds(orgCode).contains(orgId);
    }

    /** 配置机构(org_code 精确)及其全部后代机构 id 集合(机构数少,全表加载内存构建树) */
    private Set<Long> subtreeOrgIds(String orgCode) {
        List<Long> root = jdbcTemplate.queryForList(
                "SELECT id FROM ccr_sys_dept WHERE org_code = ? AND del_flag = '0'", Long.class, orgCode);
        if (root.isEmpty()) {
            return Set.of();
        }
        List<Map<String, Object>> all = jdbcTemplate.queryForList(
                "SELECT id, parent_id FROM ccr_sys_dept WHERE del_flag = '0'");
        Map<Long, List<Long>> children = new HashMap<>();
        for (Map<String, Object> row : all) {
            long id = ((Number) row.get("id")).longValue();
            long parent = ((Number) row.get("parent_id")).longValue();
            children.computeIfAbsent(parent, k -> new ArrayList<>()).add(id);
        }
        Set<Long> subtree = new HashSet<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(root.get(0));
        while (!stack.isEmpty()) {
            long cur = stack.pop();
            if (!subtree.add(cur)) {
                continue;
            }
            for (long child : children.getOrDefault(cur, List.of())) {
                stack.push(child);
            }
        }
        return subtree;
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

        /** 解析出的处理人列表(含姓名昵称,§2026-08-26 提交预览下一步审批人姓名) */
        public List<AssigneeUser> users() {
            return users;
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
