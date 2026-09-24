package com.ccr.admin.message;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.common.core.assignee.NodeAssigneeResolver;
import com.ccr.common.exception.ServiceException;
import com.ccr.message.service.NotificationService;
import com.ccr.message.service.dto.NotificationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 消费节点提醒，收件人与实际审批指派、冻结表决席位保持一致。 */
@Component
public class NodeReminderHandler {
    private static final Logger log = LoggerFactory.getLogger(NodeReminderHandler.class);
    private static final Map<String, String> NODE_NAMES = Map.of(
            "BRANCH_MANAGER", "支行行长审批", "PARENT_BRANCH_MANAGER", "管理综合支行行长审批",
            "DEPT_GENERAL_MANAGER", "部门总经理审批", "VICE_PRESIDENT", "分管行长审批",
            "SECRETARY", "秘书岗审批", "SIX_PEOPLE_GROUP", "六人小组表决", "PRESIDENT", "行长决策");
    private final CcrApplicationMapper applications;
    private final NodeAssigneeResolver assignees;
    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final WechatMessageProperties wechat;

    public NodeReminderHandler(CcrApplicationMapper applications, NodeAssigneeResolver assignees,
                               JdbcTemplate jdbc, NotificationService notifications, WechatMessageProperties wechat) {
        this.applications = applications;
        this.assignees = assignees;
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.wechat = wechat;
    }

    public void handle(JSONObject payload) {
        Long applicationId = payload.getLong("applicationId");
        String node = payload.getStr("nodeCode");
        if (applicationId == null || !NODE_NAMES.containsKey(node == null ? "" : node)
                || payload.getStr("messageKey") == null) {
            throw new ServiceException(400, "节点提醒事件参数无效");
        }
        CcrApplication app = applications.selectById(applicationId);
        if (app == null || "1".equals(app.getDelFlag())) return;
        // 延迟消费时已处理的节点不再发待办提醒；行长决策以分项状态为准。
        String pendingSql = "SELECT COUNT(*) FROM ccr_pricing_item WHERE application_id = ? AND del_flag = '0' AND ";
        Long pending = "PRESIDENT".equals(node)
                ? jdbc.queryForObject(pendingSql + "status IN ('PRESIDENT_DECISION','COMMITTEE_PASS')", Long.class, applicationId)
                : jdbc.queryForObject(pendingSql + "current_node_code = ? AND status = ?", Long.class,
                        applicationId, node, "SIX_PEOPLE_GROUP".equals(node) ? "VOTING" : "ROUTING");
        if (pending == null || pending == 0) return;
        List<Long> recipients = recipients(payload, app, node);
        // 表决席位可能已提交或替补，过期席位提醒直接结束。
        if (recipients.isEmpty() && "SIX_PEOPLE_GROUP".equals(node)) return;
        if (recipients.isEmpty()) throw new ServiceException(503, "节点提醒未解析到有效处理人:" + node);
        // 建批事件延迟消费时可能已发生替补，两种事件对同一批次同一人共享幂等键。
        String baseKey = "SIX_PEOPLE_GROUP".equals(node)
                ? "VOTE:" + applicationId + ":" + payload.getLong("roundId") : payload.getStr("messageKey");
        String content = buildContent(app);
        for (Long userId : recipients.stream().distinct().toList()) {
            send(baseKey, userId, "SYSTEM", content);
            if (wechat.isEnabled()) send(baseKey, userId, "WECHAT", content);
        }
    }

    /**
     * 待办提醒文案：【客户利率审批系统】xx支行客户xx的利率申请需要您审批，请及时审批。（申请号 xxx）
     * 支行取申请支行（apply_branch_code，与支行行长解析同一列），客户名主表可能为空，回退提交时的要素快照。
     */
    private String buildContent(CcrApplication app) {
        String branch = branchShortName(app.getApplyBranchCode());
        String customer = customerDisplayName(app);
        return "【客户利率审批系统】" + (branch == null ? "" : branch) + "客户"
                + (customer == null ? "" : customer)
                + "的利率申请需要您审批，请及时审批。（申请号 " + app.getApplicationNo() + "）";
    }

    /** 申请支行简称：机构名剥掉行名前缀（"江苏宜兴农村商业银行官林支行"→"官林支行"）；查不到或查询异常返回 null。 */
    private String branchShortName(String applyBranchCode) {
        if (StrUtil.isBlank(applyBranchCode)) return null;
        try {
            List<String> names = jdbc.queryForList("""
                    SELECT d.dept_name FROM ccr_sys_dept d
                    WHERE d.del_flag = '0' AND d.org_type = 'BRANCH'
                      AND d.branch_code IS NOT NULL AND TRIM(d.branch_code) <> ''
                      AND LEFT(?, CHAR_LENGTH(d.branch_code)) = d.branch_code
                    ORDER BY CHAR_LENGTH(d.branch_code) DESC LIMIT 1
                    """, String.class, applyBranchCode.trim());
            if (names.isEmpty() || StrUtil.isBlank(names.get(0))) return null;
            String full = names.get(0).trim();
            // 剥掉"…银行"（含"银行股份有限公司"写法）之前的部分，剥完为空则保留全称。
            String shortName = full.replaceFirst("^.*?银行(?:股份有限公司)?", "").trim();
            return StrUtil.isBlank(shortName) ? full : shortName;
        } catch (DataAccessException e) {
            log.warn("解析申请支行名称失败，applyBranchCode={}：{}", applyBranchCode, e.getMessage());
            return null;
        }
    }

    /**
     * 消息展示用客户名：主表为空时依次回退 客户信息快照→集团信息快照→集团号→客户号（与审批列表口径一致）；
     * 全空返回 null，由调用方省略，避免拼出"客户该客户"这类重复。
     */
    private String customerDisplayName(CcrApplication app) {
        if (StrUtil.isNotBlank(app.getCustomerName())) return app.getCustomerName();
        String name = jsonName(app.getCustomerInfoJson(), "customerName");
        if (StrUtil.isNotBlank(name)) return name;
        name = jsonName(app.getGroupInfoJson(), "groupName");
        if (StrUtil.isNotBlank(name)) return name;
        if (StrUtil.isNotBlank(app.getGroupNo())) return app.getGroupNo();
        return StrUtil.isBlank(app.getCustomerNo()) ? null : app.getCustomerNo();
    }

    private String jsonName(String json, String key) {
        if (StrUtil.isBlank(json)) return null;
        try {
            return JSONUtil.parseObj(json).getStr(key);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Long> recipients(JSONObject payload, CcrApplication app, String node) {
        if ("SIX_PEOPLE_GROUP".equals(node)) {
            Long roundId = payload.getLong("roundId");
            Long userId = payload.getLong("userId");
            String sql = """
                    SELECT a.voter_user_id FROM ccr_vote_assignment a
                    JOIN ccr_vote_round r ON r.id = a.round_id AND r.del_flag = '0'
                    JOIN ccr_sys_user u ON u.id = a.voter_user_id AND u.status = 'ENABLE' AND u.del_flag = '0'
                    WHERE r.application_id = ? AND r.id = ? AND r.status = 'VOTING'
                      AND a.status = 'PENDING' AND a.del_flag = '0'
                    """;
            return userId == null ? jdbc.queryForList(sql, Long.class, app.getId(), roundId)
                    : jdbc.queryForList(sql + " AND a.voter_user_id = ?", Long.class, app.getId(), roundId, userId);
        }
        List<Long> ids = assignees.resolveUserIds(node, app.getApplicantOrgId(), app.getDeptCode());
        if ("BRANCH_MANAGER".equals(node)
                && com.ccr.common.core.util.BranchTypeSupport.isRetailBranch(jdbc, app.getApplicantOrgId())) {
            return ids;
        }
        if (!ids.isEmpty()) {
            // 行长决策仍要求 president 主角色，不能仅凭节点配置获得决策权。
            if ("PRESIDENT".equals(node)) {
                List<Long> presidents = enabledRole("president");
                return ids.stream().filter(presidents::contains).toList();
            }
            return ids;
        }
        if ("BRANCH_MANAGER".equals(node)) {
            return jdbc.queryForList("""
                    SELECT u.id FROM ccr_sys_user u
                    JOIN ccr_sys_dept d ON d.id = u.org_id AND d.del_flag = '0'
                    WHERE u.role_code = 'branch_manager' AND u.status = 'ENABLE' AND u.del_flag = '0'
                      AND d.branch_code IS NOT NULL AND TRIM(d.branch_code) <> ''
                      AND LEFT(?, CHAR_LENGTH(d.branch_code)) = d.branch_code
                    ORDER BY u.id
                    """, Long.class, app.getApplyBranchCode());
        }
        // 部门类及管理综合支行节点禁止扩大范围；沿用业务授权的配置拒绝口径。
        return switch (node) {
            case "PRESIDENT" -> enabledRole("president");
            case "SECRETARY" -> enabledRole("secretary");
            default -> List.of();
        };
    }

    private List<Long> enabledRole(String role) {
        return jdbc.queryForList("SELECT id FROM ccr_sys_user WHERE role_code = ? AND status = 'ENABLE' AND del_flag = '0' ORDER BY id",
                Long.class, role);
    }

    private void send(String baseKey, Long userId, String channel, String content) {
        NotificationMessage message = new NotificationMessage();
        message.setRecipientType("USER");
        message.setRecipientId(userId.toString());
        message.setChannel(channel);
        message.setContent(content);
        message.setMessageKey("NR:" + DigestUtil.sha256Hex(baseKey + "|" + userId + "|" + channel).substring(0, 60));
        notifications.sendNotification(message);
    }
}
