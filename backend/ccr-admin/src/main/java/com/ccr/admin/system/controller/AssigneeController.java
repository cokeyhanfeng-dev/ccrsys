package com.ccr.admin.system.controller;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.ccr.common.core.assignee.NodeAssigneeResolver;
import com.ccr.common.core.domain.R;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.vote.read.SysUserRead;
import com.ccr.vote.support.CurrentLoginUser;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 节点审批人配置(§5.5.1/§11.11/§12.17,仅 admin 角色)
 * 指派 CRUD(唯一性校验 + version_no 乐观锁) + 代理人设置(审计留痕) + 解析预览。
 * 四层解析:PERSON → GROUP → DEPT → ROLE,命中即止;未配置=不限制(角色兜底)。
 * 全部增删改/代理变更写 ccr_audit_log(§15.3)。
 */
@RestController
@RequestMapping("/system/flow")
@Slf4j
public class AssigneeController {

    /** 节点清单(编码 → 中文名) */
    private static final Map<String, String> NODE_NAMES = new LinkedHashMap<>() {{
            put("BRANCH_MANAGER", "支行行长");
            put("DEPT_GENERAL_MANAGER", "部门总经理");
            put("VICE_PRESIDENT", "分管行长");
            put("SIX_PEOPLE_GROUP", "六人小组");
            put("PRESIDENT", "总行行长");
            put("SECRETARY", "秘书");
        }};

    private static final Set<String> ASSIGNEE_TYPES = Set.of("PERSON", "GROUP", "DEPT", "ROLE");

    private static final Set<String> RELATIONS = Set.of("AND", "OR");

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private CurrentLoginUser currentLoginUser;

    @Resource
    private NodeAssigneeResolver nodeAssigneeResolver;

    /** 节点清单:编码 + 中文名 + 当前有效指派数 */
    @GetMapping("/nodes")
    public R<List<Map<String, Object>>> nodes() {
        requireAdmin();
        Map<String, Long> counts = new LinkedHashMap<>();
        try {
            for (Map<String, Object> row : jdbcTemplate.queryForList(
                    "SELECT node_code nodeCode, COUNT(*) cnt FROM ccr_node_assignee"
                            + " WHERE del_flag = '0' AND status = 'ACTIVE' GROUP BY node_code")) {
                counts.put(String.valueOf(row.get("nodeCode")), ((Number) row.get("cnt")).longValue());
            }
        } catch (DataAccessException e) {
            // 表不存在(未执行 03f):全部按 0 返回
            log.warn("节点指派统计查询失败,按 0 返回: {}", e.getMessage());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        NODE_NAMES.forEach((nodeCode, nodeName) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nodeCode", nodeCode);
            row.put("nodeName", nodeName);
            row.put("assigneeCount", counts.getOrDefault(nodeCode, 0L));
            result.add(row);
        });
        return R.ok(result);
    }

    /** 指派列表(筛选:节点/类型/状态) */
    @GetMapping("/assignees")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String nodeCode,
                                             @RequestParam(required = false) String assigneeType,
                                             @RequestParam(required = false) String status) {
        requireAdmin();
        StringBuilder sql = new StringBuilder("""
                SELECT id, flow_key flowKey, node_code nodeCode, assignee_type assigneeType,
                       assignee_code assigneeCode, relation, delegate_to delegateTo,
                       delegate_valid_from delegateValidFrom, delegate_valid_to delegateValidTo,
                       valid_from validFrom, valid_to validTo, status, remark, version_no versionNo,
                       create_by createBy, create_time createTime, update_by updateBy, update_time updateTime
                FROM ccr_node_assignee
                WHERE del_flag = '0'
                """);
        List<Object> args = new ArrayList<>();
        if (StrUtil.isNotBlank(nodeCode)) {
            sql.append(" AND node_code = ?");
            args.add(nodeCode);
        }
        if (StrUtil.isNotBlank(assigneeType)) {
            sql.append(" AND assignee_type = ?");
            args.add(assigneeType);
        }
        if (StrUtil.isNotBlank(status)) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY node_code, id");
        return R.ok(jdbcTemplate.queryForList(sql.toString(), args.toArray()));
    }

    /** 新增指派(唯一性:flow_key+node_code+assignee_type+assignee_code+valid_from) */
    @PostMapping("/assignees")
    public R<Long> create(@RequestBody Map<String, Object> body) {
        SysUserRead operator = requireAdmin();
        validateAssignee(body);
        String flowKey = str(body.get("flowKey"));
        String nodeCode = str(body.get("nodeCode"));
        String assigneeType = str(body.get("assigneeType"));
        String assigneeCode = str(body.get("assigneeCode"));
        String validFrom = str(body.get("validFrom"));
        checkDuplicate(flowKey, nodeCode, assigneeType, assigneeCode, validFrom, null);

        long id = IdUtil.getSnowflakeNextId();
        try {
            jdbcTemplate.update("""
                            INSERT INTO ccr_node_assignee
                            (id, flow_key, node_code, assignee_type, assignee_code, relation,
                             delegate_to, delegate_valid_from, delegate_valid_to,
                             valid_from, valid_to, status, remark, version_no, create_by, create_time)
                            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                            """,
                    id, flowKey, nodeCode, assigneeType, assigneeCode,
                    StrUtil.blankToDefault(str(body.get("relation")), "OR"),
                    null, null, null,
                    validFrom, str(body.get("validTo")),
                    StrUtil.blankToDefault(str(body.get("status")), "ACTIVE"),
                    str(body.get("remark")), 1, operator.getId(), LocalDateTime.now());
        } catch (DuplicateKeyException e) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "指派已存在(同流程/节点/类型/对象/生效起日)");
        }
        writeAuditLog("ASSIGNEE_CHANGE", String.valueOf(id),
                "新增指派:节点=" + nodeCode + ",类型=" + assigneeType + ",对象=" + assigneeCode, operator);
        return R.ok(id);
    }

    /** 修改指派(乐观锁 version_no) */
    @PutMapping("/assignees/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        SysUserRead operator = requireAdmin();
        validateAssignee(body);
        Object versionNo = body.get("versionNo");
        if (versionNo == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "版本号 versionNo 必传");
        }
        String flowKey = str(body.get("flowKey"));
        String nodeCode = str(body.get("nodeCode"));
        String assigneeType = str(body.get("assigneeType"));
        String assigneeCode = str(body.get("assigneeCode"));
        String validFrom = str(body.get("validFrom"));
        checkDuplicate(flowKey, nodeCode, assigneeType, assigneeCode, validFrom, id);

        int rows = jdbcTemplate.update("""
                        UPDATE ccr_node_assignee
                        SET flow_key = ?, node_code = ?, assignee_type = ?, assignee_code = ?,
                            relation = ?, valid_from = ?, valid_to = ?, status = ?, remark = ?,
                            version_no = version_no + 1, update_by = ?, update_time = ?
                        WHERE id = ? AND version_no = ? AND del_flag = '0'
                        """,
                flowKey, nodeCode, assigneeType, assigneeCode,
                StrUtil.blankToDefault(str(body.get("relation")), "OR"),
                validFrom, str(body.get("validTo")),
                StrUtil.blankToDefault(str(body.get("status")), "ACTIVE"),
                str(body.get("remark")), operator.getId(), LocalDateTime.now(),
                id, ((Number) versionNo).intValue());
        if (rows == 0) {
            throw new ServiceException(ErrorCode.DATA_VERSION_CONFLICT.getCode(),
                    "指派数据版本冲突或已删除,请刷新后重试");
        }
        writeAuditLog("ASSIGNEE_CHANGE", String.valueOf(id),
                "修改指派:节点=" + nodeCode + ",类型=" + assigneeType + ",对象=" + assigneeCode, operator);
        return R.ok();
    }

    /** 删除指派(逻辑删除) */
    @DeleteMapping("/assignees/{id}")
    public R<Void> delete(@PathVariable Long id) {
        SysUserRead operator = requireAdmin();
        int rows = jdbcTemplate.update(
                "UPDATE ccr_node_assignee SET del_flag = '1', update_by = ?, update_time = ?"
                        + " WHERE id = ? AND del_flag = '0'",
                operator.getId(), LocalDateTime.now(), id);
        if (rows == 0) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "指派不存在或已删除");
        }
        writeAuditLog("ASSIGNEE_CHANGE", String.valueOf(id), "删除指派", operator);
        return R.ok();
    }

    /**
     * 设置/取消代理人(运行期,§5.5.1):delegateTo 非空=设置代理(带有效期,空边界=该侧不限),
     * 空=取消代理;写审计留痕
     */
    @PostMapping("/assignees/{id}/delegate")
    public R<Void> delegate(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        SysUserRead operator = requireAdmin();
        String delegateTo = str(body.get("delegateTo"));
        String validFrom = str(body.get("validFrom"));
        String validTo = str(body.get("validTo"));
        if (StrUtil.isNotBlank(delegateTo)) {
            // 代理人必须是启用用户(按工号)
            Long cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ccr_sys_user WHERE username = ? AND status = 'ENABLE' AND del_flag = '0'",
                    Long.class, delegateTo);
            if (cnt == null || cnt == 0) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "代理人不存在或已停用:" + delegateTo);
            }
        }
        int rows = jdbcTemplate.update("""
                        UPDATE ccr_node_assignee
                        SET delegate_to = ?, delegate_valid_from = ?, delegate_valid_to = ?,
                            version_no = version_no + 1, update_by = ?, update_time = ?
                        WHERE id = ? AND del_flag = '0'
                        """,
                delegateTo, validFrom, validTo, operator.getId(), LocalDateTime.now(), id);
        if (rows == 0) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "指派不存在或已删除");
        }
        writeAuditLog("DELEGATE", String.valueOf(id),
                StrUtil.isBlank(delegateTo) ? "取消代理人"
                        : "设置代理人:" + delegateTo + ",有效期=" + validFrom + "~" + validTo, operator);
        return R.ok();
    }

    /**
     * 解析预览(§12.17):输入节点 + 申请人机构(+可选流程key),
     * 返回命中层级与实际处理人列表(含代理展开);空列表=不限制(角色兜底)
     */
    @PostMapping("/assignees/resolve")
    public R<NodeAssigneeResolver.ResolveResult> resolve(@RequestBody Map<String, Object> body) {
        requireAdmin();
        String nodeCode = str(body.get("nodeCode"));
        if (StrUtil.isBlank(nodeCode) || !NODE_NAMES.containsKey(nodeCode)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "节点编码必填且须为已知审批节点");
        }
        Object orgId = body.get("orgId");
        String flowKey = str(body.get("flowKey"));
        return R.ok(nodeAssigneeResolver.resolve(nodeCode,
                orgId == null ? null : ((Number) orgId).longValue(), flowKey));
    }

    // ---------- 部门-分管行领导映射(§D16a;一人可分管多部门,纯配置,2026-08-14 配置界面化) ----------

    /** 分管行长映射列表(关联机构名/分管行长姓名) */
    @GetMapping("/dept-vp")
    public R<List<Map<String, Object>>> listDeptVp() {
        requireAdmin();
        return R.ok(jdbcTemplate.queryForList("""
                SELECT vp.id, vp.dept_code deptCode, d.dept_name deptName,
                       vp.vp_user_id vpUserId, u.username vpUsername, u.nick_name vpNickName,
                       vp.status, vp.valid_from validFrom, vp.valid_to validTo,
                       vp.version_no versionNo, vp.create_time createTime
                FROM ccr_dept_vp vp
                LEFT JOIN ccr_sys_dept d ON d.org_code = vp.dept_code AND d.del_flag = '0'
                LEFT JOIN ccr_sys_user u ON u.id = vp.vp_user_id
                WHERE vp.del_flag = '0'
                ORDER BY vp.dept_code
                """));
    }

    /** 新增分管行长映射(部门+分管行长;一人可分管多部门) */
    @PostMapping("/dept-vp")
    public R<Long> createDeptVp(@RequestBody Map<String, Object> body) {
        SysUserRead operator = requireAdmin();
        String deptCode = str(body.get("deptCode"));
        Long vpUserId = body.get("vpUserId") == null ? null : ((Number) body.get("vpUserId")).longValue();
        validateDeptVp(deptCode, vpUserId);
        long id = IdUtil.getSnowflakeNextId();
        jdbcTemplate.update("""
                        INSERT INTO ccr_dept_vp
                        (id, tenant_id, dept_code, vp_user_id, status, valid_from, valid_to,
                         version_no, create_by, create_time)
                        VALUES (?,?,?,?,?,?,?,?,?,?)
                        """,
                id, "000000", deptCode, vpUserId,
                StrUtil.blankToDefault(str(body.get("status")), "ACTIVE"),
                str(body.get("validFrom")), str(body.get("validTo")),
                1, operator.getId(), LocalDateTime.now());
        writeAuditLog("ASSIGNEE_CHANGE", String.valueOf(id),
                "新增分管行长映射:部门=" + deptCode + ",分管行长=" + vpUserId, operator);
        return R.ok(id);
    }

    /** 修改分管行长映射(乐观锁 version_no) */
    @PutMapping("/dept-vp/{id}")
    public R<Void> updateDeptVp(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        SysUserRead operator = requireAdmin();
        String deptCode = str(body.get("deptCode"));
        Long vpUserId = body.get("vpUserId") == null ? null : ((Number) body.get("vpUserId")).longValue();
        validateDeptVp(deptCode, vpUserId);
        Object versionNo = body.get("versionNo");
        if (versionNo == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "版本号 versionNo 必传");
        }
        int rows = jdbcTemplate.update("""
                        UPDATE ccr_dept_vp
                        SET dept_code = ?, vp_user_id = ?, status = ?, valid_from = ?, valid_to = ?,
                            version_no = version_no + 1, update_by = ?, update_time = ?
                        WHERE id = ? AND version_no = ? AND del_flag = '0'
                        """,
                deptCode, vpUserId,
                StrUtil.blankToDefault(str(body.get("status")), "ACTIVE"),
                str(body.get("validFrom")), str(body.get("validTo")),
                operator.getId(), LocalDateTime.now(), id, ((Number) versionNo).intValue());
        if (rows == 0) {
            throw new ServiceException(ErrorCode.DATA_VERSION_CONFLICT.getCode(),
                    "分管行长映射数据版本冲突或已删除,请刷新后重试");
        }
        writeAuditLog("ASSIGNEE_CHANGE", String.valueOf(id),
                "修改分管行长映射:部门=" + deptCode + ",分管行长=" + vpUserId, operator);
        return R.ok();
    }

    /** 删除分管行长映射(逻辑删除) */
    @DeleteMapping("/dept-vp/{id}")
    public R<Void> deleteDeptVp(@PathVariable Long id) {
        SysUserRead operator = requireAdmin();
        int rows = jdbcTemplate.update(
                "UPDATE ccr_dept_vp SET del_flag = '1', update_by = ?, update_time = ?"
                        + " WHERE id = ? AND del_flag = '0'",
                operator.getId(), LocalDateTime.now(), id);
        if (rows == 0) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "分管行长映射不存在或已删除");
        }
        writeAuditLog("ASSIGNEE_CHANGE", String.valueOf(id), "删除分管行长映射", operator);
        return R.ok();
    }

    // ---------- 私有 ----------

    /** 仅 admin 角色(流程配置管理) */
    private SysUserRead requireAdmin() {
        currentLoginUser.requireAnyRole(CurrentLoginUser.ROLE_ADMIN);
        return currentLoginUser.requireCurrentUser();
    }

    /** 必填与枚举校验 */
    private void validateAssignee(Map<String, Object> body) {
        String nodeCode = str(body.get("nodeCode"));
        String assigneeType = str(body.get("assigneeType"));
        String assigneeCode = str(body.get("assigneeCode"));
        if (StrUtil.isBlank(nodeCode) || !NODE_NAMES.containsKey(nodeCode)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "节点编码必填且须为已知审批节点");
        }
        if (StrUtil.isBlank(assigneeType) || !ASSIGNEE_TYPES.contains(assigneeType)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "指派类型仅支持 PERSON/GROUP/DEPT/ROLE");
        }
        if (StrUtil.isBlank(assigneeCode)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "指派对象(工号/组编码/机构码/角色码)必填");
        }
        String relation = str(body.get("relation"));
        if (StrUtil.isNotBlank(relation) && !RELATIONS.contains(relation)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "关系仅支持 AND/OR");
        }
        // PERSON 按工号直接指派:保存前校验指派人状态(§12.17 ⑤)
        if ("PERSON".equals(assigneeType)) {
            Long cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ccr_sys_user WHERE username = ? AND status = 'ENABLE' AND del_flag = '0'",
                    Long.class, assigneeCode);
            if (cnt == null || cnt == 0) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                        "指派人不存在或已停用:" + assigneeCode);
            }
        }
    }

    /** 唯一性校验(应用层;空 flow_key/valid_from 归一比较,excludeId 用于修改场景) */
    private void checkDuplicate(String flowKey, String nodeCode, String assigneeType,
                                String assigneeCode, String validFrom, Long excludeId) {
        String sql = """
                SELECT COUNT(*) FROM ccr_node_assignee
                WHERE COALESCE(flow_key, '') = COALESCE(?, '')
                  AND node_code = ? AND assignee_type = ? AND assignee_code = ?
                  AND COALESCE(valid_from, '1000-01-01') = COALESCE(?, '1000-01-01')
                  AND del_flag = '0'
                """ + (excludeId == null ? "" : " AND id <> " + excludeId);
        Long cnt = jdbcTemplate.queryForObject(sql, Long.class,
                flowKey, nodeCode, assigneeType, assigneeCode, validFrom);
        if (cnt != null && cnt > 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                    "指派已存在(同流程/节点/类型/对象/生效起日)");
        }
    }

    /** 审计留痕(§15.3);表不存在(未执行 03f)时仅记日志,不阻断操作 */
    private void writeAuditLog(String logType, String bizId, String content, SysUserRead operator) {
        try {
            jdbcTemplate.update("""
                            INSERT INTO ccr_audit_log
                            (id, log_type, biz_id, content, operator_id, operator_name, operate_time)
                            VALUES (?,?,?,?,?,?,?)
                            """,
                    IdUtil.getSnowflakeNextId(), logType, bizId, content,
                    operator.getId(), operator.getNickName(), LocalDateTime.now());
        } catch (DataAccessException e) {
            log.warn("审计留痕写入失败(不影响操作): {}", e.getMessage());
        }
    }

    /** 请求值 → 去空白字符串(空串归一为 null) */
    private String str(Object value) {
        return value == null ? null : StrUtil.blankToDefault(value.toString().trim(), null);
    }

    /** 分管行长映射校验:部门机构码存在 + 分管行长须为启用 vice_president 用户 */
    private void validateDeptVp(String deptCode, Long vpUserId) {
        if (StrUtil.isBlank(deptCode)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "部门必选");
        }
        if (vpUserId == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "分管行长必选");
        }
        Long deptCnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ccr_sys_dept WHERE org_code = ? AND del_flag = '0'",
                Long.class, deptCode);
        if (deptCnt == null || deptCnt == 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "部门不存在:" + deptCode);
        }
        Long vpCnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ccr_sys_user WHERE id = ? AND role_code = 'vice_president'"
                        + " AND status = 'ENABLE' AND del_flag = '0'",
                Long.class, vpUserId);
        if (vpCnt == null || vpCnt == 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "分管行长须为启用状态的分管行长角色用户");
        }
    }
}
