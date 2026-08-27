package com.ccr.approval.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.ccr.application.support.AppLoginUser;
import com.ccr.approval.support.RouteChains;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 流程监控(运行监控「流程监控」tab):在途审批流程整体监控。
 * 每条流程 = 一笔申请(按申请聚合,取主分项展示金额/产品/利率),展示:
 * 1) 当前走到哪一步(nodes 节点状态时间线 + currentNodeCode/currentNodeLabel);
 * 2) 为什么走到当前节点(routeReason 链路形态 + currentReason 当前节点规则原因)。
 * 原因口径 = 仅路由规则原因(业务类型/金额档/利率/集团/硬边界等系统规则推导),不含审批意见;
 * 在途 = 分项状态 ROUTING/VOTING/COMMITTEE_PASS/PRESIDENT_DECISION(排除终态)。
 * N+1(每行 buildNodes/buildReason 多查分项与表决)为已知取舍,MVP 默认 size=10。
 */
@Service
public class FlowMonitorService {

    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private AppLoginUser appLoginUser;

    /** 在途分项状态(排除终态):路由中/表决中/已过会待行长决策/行长决策 */
    private static final List<String> IN_FLIGHT = List.of("ROUTING", "VOTING", "COMMITTEE_PASS", "PRESIDENT_DECISION");

    /** 节点中文名(审批进度可视化;含贷审会秘书岗/总行行长) */
    private static final Map<String, String> NODE_LABEL = Map.of(
            "BRANCH_MANAGER", "支行行长",
            "DEPT_GENERAL_MANAGER", "部门总经理",
            "VICE_PRESIDENT", "总行分管行长",
            "SECRETARY", "贷审会秘书岗",
            "SIX_PEOPLE_GROUP", "审批小组成员",
            "PRESIDENT", "总行行长");

    /** 节点规范顺序(与流程定义 flow_node 一致;多分项链路并集后按此重排,避免缺失节点被 append 到链尾错位) */
    private static final List<String> NODE_ORDER = List.of(
            "BRANCH_MANAGER", "DEPT_GENERAL_MANAGER", "VICE_PRESIDENT", "SECRETARY",
            "SIX_PEOPLE_GROUP", "PRESIDENT");

    /** 终审岗位中文名(route_code 截断文案用) */
    private static final Map<String, String> FINAL_ROLE_LABEL = Map.of(
            "BRANCH_MANAGER", "支行行长",
            "DEPT_GENERAL_MANAGER", "部门总经理",
            "VICE_PRESIDENT", "总行分管行长",
            "SIX_PEOPLE_GROUP", "六人小组");

    /**
     * 分页查询在途流程(按申请聚合,取主分项展示金额/产品/利率),每条含 nodes 节点状态 + 路由原因文案。
     */
    public Map<String, Object> pageFlows(int page, int size, String status, String businessType, String applicationNo) {
        StringBuilder where = new StringBuilder("a.del_flag = '0'");
        List<Object> args = new ArrayList<>();
        if (StrUtil.isNotBlank(status)) {
            where.append(" AND EXISTS (SELECT 1 FROM ccr_pricing_item pi WHERE pi.application_id = a.id"
                    + " AND pi.del_flag = '0' AND pi.status = ?)");
            args.add(status);
        } else {
            where.append(" AND EXISTS (SELECT 1 FROM ccr_pricing_item pi WHERE pi.application_id = a.id"
                    + " AND pi.del_flag = '0' AND pi.status IN ('ROUTING','VOTING','COMMITTEE_PASS','PRESIDENT_DECISION'))");
        }
        if (StrUtil.isNotBlank(businessType)) {
            where.append(" AND a.business_type = ?");
            args.add(businessType);
        }
        if (StrUtil.isNotBlank(applicationNo)) {
            where.append(" AND a.application_no LIKE CONCAT('%', ?, '%')");
            args.add(applicationNo);
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ccr_application a WHERE " + where, Long.class, args.toArray());

        args.add(size);
        args.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT a.id applicationId, a.application_no applicationNo, a.business_type businessType,"
                        + " a.customer_scope customerScope, a.customer_no customerNo, a.group_no groupNo,"
                        + " a.status, a.submit_time submitTime"
                        + " FROM ccr_application a WHERE " + where
                        + " ORDER BY a.submit_time DESC LIMIT ? OFFSET ?",
                args.toArray());

        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> app : rows) {
            records.add(buildFlow(app));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0L : total);
        result.put("records", records);
        return result;
    }

    /** 组装单条流程(FlowVO):申请 + 主分项 + 节点时间线 + 路由原因文案 */
    private Map<String, Object> buildFlow(Map<String, Object> app) {
        Long applicationId = Long.valueOf(String.valueOf(app.get("applicationId")));
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("applicationId", applicationId);
        flow.put("applicationNo", app.get("applicationNo"));
        flow.put("businessType", app.get("businessType"));
        flow.put("customerScope", app.get("customerScope"));
        flow.put("customerNo", app.get("customerNo"));
        flow.put("groupNo", app.get("groupNo"));
        flow.put("submitTime", app.get("submitTime"));

        List<Map<String, Object>> items = itemsOf(applicationId);
        if (items.isEmpty()) {
            flow.put("status", app.get("status"));
            flow.put("routeReason", "");
            flow.put("currentReason", "");
            flow.put("nodes", List.of());
            return flow;
        }
        Map<String, Object> nodeResult = buildNodes(applicationId);
        @SuppressWarnings("unchecked")
        List<String> chain = (List<String>) nodeResult.get("routeChain");
        String curNode = (String) nodeResult.get("currentNodeCode");
        Map<String, Object> main = mainItemOf(items, chain);
        Map<String, Object> reason = buildReason(app, main, chain, curNode);

        flow.put("status", main.get("status"));
        flow.put("pricingItemId", main.get("id"));
        flow.put("productCode", main.get("productCode"));
        flow.put("amount", main.get("pricingAmount"));
        flow.put("currentApprovalRate", main.get("currentApprovalRate"));
        flow.put("requestedRate", main.get("requestedRate"));
        flow.put("routeCode", main.get("routeCode"));
        flow.put("currentNodeCode", curNode);
        flow.put("currentNodeLabel", curNode == null ? null : NODE_LABEL.getOrDefault(curNode, curNode));
        flow.put("routeReason", reason.get("routeReason"));
        flow.put("currentReason", reason.get("currentReason"));
        flow.put("nodes", nodeResult.get("nodes"));
        return flow;
    }

    /** 申请的分项明细(展示 + 节点计算) */
    private List<Map<String, Object>> itemsOf(Long applicationId) {
        return jdbcTemplate.queryForList(
                "SELECT id, pricing_item_no pricingItemNo, product_code productCode, pricing_amount pricingAmount,"
                        + " requested_rate requestedRate, current_approval_rate currentApprovalRate,"
                        + " route_code routeCode, route_chain routeChain, current_node_code currentNodeCode, status"
                        + " FROM ccr_pricing_item WHERE application_id = ? AND del_flag = '0' ORDER BY id",
                applicationId);
    }

    /**
     * 主分项:优先 current_node_code 在链上推进最深的(与当前节点口径一致),同深度取金额大者;
     * 均无推进(异常)取金额最大分项。
     */
    private Map<String, Object> mainItemOf(List<Map<String, Object>> items, List<String> chain) {
        Map<String, Object> best = null;
        int bestIdx = -1;
        for (Map<String, Object> it : items) {
            Object c = it.get("currentNodeCode");
            int idx = -1;
            if (c != null) {
                idx = chain.indexOf(c.toString());
            }
            if (idx > bestIdx || (idx == bestIdx && best != null && gtAmount(it, best))) {
                best = it;
                bestIdx = idx;
            }
        }
        if (best == null) {
            for (Map<String, Object> it : items) {
                if (best == null || gtAmount(it, best)) {
                    best = it;
                }
            }
        }
        return best;
    }

    private boolean gtAmount(Map<String, Object> a, Map<String, Object> b) {
        BigDecimal av = toDecimal(a.get("pricingAmount"));
        BigDecimal bv = toDecimal(b.get("pricingAmount"));
        if (av == null) {
            return false;
        }
        return bv == null || av.compareTo(bv) > 0;
    }

    /**
     * 审批进度节点状态(与审批进度接口同一套逻辑,运行监控复用)。
     * 在途判定覆盖 ROUTING/VOTING/COMMITTEE_PASS/PRESIDENT_DECISION;
     * 已过会(COMMITTEE_PASS/PRESIDENT_DECISION)且链含行长节点时,当前节点回退为总行行长决策。
     */
    public Map<String, Object> buildNodes(Long applicationId) {
        List<Map<String, Object>> apps = jdbcTemplate.queryForList(
                "SELECT id, application_no applicationNo, business_type businessType, status, submit_time submitTime"
                        + " FROM ccr_application WHERE id = ? AND del_flag = '0'", applicationId);
        if (apps.isEmpty()) {
            throw new ServiceException(404, "申请不存在");
        }
        Map<String, Object> app = apps.get(0);
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT id, pricing_item_no pricingItemNo, route_chain routeChain, current_node_code currentNodeCode, status"
                        + " FROM ccr_pricing_item WHERE application_id = ? AND del_flag = '0' ORDER BY id", applicationId);

        // 链路并集(保持冻结顺序;多分项链路不一致时取并集)
        List<String> chain = new ArrayList<>();
        for (Map<String, Object> it : items) {
            Object rc = it.get("routeChain");
            if (rc != null && StrUtil.isNotBlank(rc.toString())) {
                for (String n : JSONUtil.parseArray(rc.toString()).toList(String.class)) {
                    if (!chain.contains(n)) {
                        chain.add(n);
                    }
                }
            }
        }
        // §2026-08-26 修复:并集按规范节点顺序重排——多分项 route_chain 不一致时(如某分项因金额/利率未触发
        // 秘书岗而链路不含 SECRETARY、另一分项含 SECRETARY),后遍历分项缺失的节点会被 append 到链尾,
        // 曾导致「贷审会秘书岗」显示在「总行行长」之后;重排保证秘书岗固定于分管行长后、六人小组前。
        // 不在规范顺序内的自定义节点保持原相对顺序排到末尾。
        chain.sort(Comparator.comparingInt(n -> {
            int i = NODE_ORDER.indexOf(n);
            return i < 0 ? NODE_ORDER.size() : i;
        }));
        // 当前节点:任一分项流转中(ROUTING/VOTING/COMMITTEE_PASS/PRESIDENT_DECISION)时取链上最靠后的 current_node_code;全终态则链路全部 DONE
        int curIdx = -1;
        String curNode = null;
        boolean anyRouting = false;
        boolean postVote = false;
        for (Map<String, Object> it : items) {
            String s = it.get("status") == null ? null : it.get("status").toString();
            if (IN_FLIGHT.contains(s)) {
                anyRouting = true;
            }
            if ("COMMITTEE_PASS".equals(s) || "PRESIDENT_DECISION".equals(s)) {
                postVote = true;
            }
            String c = it.get("currentNodeCode") == null ? null : it.get("currentNodeCode").toString();
            if (c != null) {
                int idx = chain.indexOf(c);
                if (idx > curIdx) {
                    curIdx = idx;
                    curNode = c;
                }
            }
        }
        // 已过会(小组通过/行长决策中)且链含行长节点时,当前节点为总行行长决策
        if (postVote && chain.contains("PRESIDENT") && !"PRESIDENT".equals(curNode)) {
            curNode = "PRESIDENT";
            curIdx = chain.indexOf("PRESIDENT");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applicationId", applicationId);
        result.put("applicationNo", app.get("applicationNo"));
        result.put("businessType", app.get("businessType"));
        result.put("currentStatus", app.get("status"));
        result.put("currentNodeCode", anyRouting ? curNode : null);
        result.put("routeChain", chain);
        if (items.isEmpty()) {
            result.put("nodes", List.of());
            return result;
        }

        // 普通节点审批动作(节点 → 最后动作操作人/时间)
        StringBuilder inSb = new StringBuilder();
        for (Map<String, Object> it : items) {
            if (inSb.length() > 0) {
                inSb.append(',');
            }
            inSb.append(it.get("id"));
        }
        String in = inSb.toString();
        Map<String, Map<String, Object>> lastByNode = new HashMap<>();
        Set<String> handledNodes = new LinkedHashSet<>();
        for (Map<String, Object> a : jdbcTemplate.queryForList(
                "SELECT a.node_code nodeCode, a.operation_time operationTime, u.nick_name operatorName"
                        + " FROM ccr_approval_action a LEFT JOIN ccr_sys_user u ON u.id = a.operator_id"
                        + " WHERE a.pricing_item_id IN (" + in + ") AND a.action_type IN ('APPROVE','REJECT','VETO','ESCALATE')"
                        + " AND a.del_flag = '0' ORDER BY a.operation_time")) {
            lastByNode.put(String.valueOf(a.get("nodeCode")), a);
            handledNodes.add(String.valueOf(a.get("nodeCode")));
        }
        // 表决节点:是否已计票 + 最新轮次(进行中:应投/已投/通过线)
        Integer votedResultCnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ccr_vote_result vr JOIN ccr_pricing_item pi ON pi.id = vr.pricing_item_id"
                        + " WHERE pi.application_id = ? AND vr.del_flag = '0'", Integer.class, applicationId);
        boolean voteCounted = votedResultCnt != null && votedResultCnt > 0;
        String voteResult = null;
        if (voteCounted) {
            List<Map<String, Object>> vr = jdbcTemplate.queryForList(
                    "SELECT vr.result FROM ccr_vote_result vr JOIN ccr_pricing_item pi ON pi.id = vr.pricing_item_id"
                            + " WHERE pi.application_id = ? AND vr.del_flag = '0' ORDER BY vr.count_time DESC LIMIT 1",
                    applicationId);
            if (!vr.isEmpty()) {
                voteResult = String.valueOf(vr.get(0).get("result"));
            }
        }
        Map<String, Object> round = null;
        List<Map<String, Object>> rounds = jdbcTemplate.queryForList(
                "SELECT id, round_no roundNo, round_name roundName, status, voter_count voterCount, required_count requiredCount"
                        + " FROM ccr_vote_round WHERE application_id = ? AND del_flag = '0' ORDER BY round_no DESC LIMIT 1",
                applicationId);
        if (!rounds.isEmpty()) {
            round = rounds.get(0);
        }
        // 行长决策
        Integer presCnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ccr_president_decision pd JOIN ccr_pricing_item pi ON pi.id = pd.pricing_item_id"
                        + " WHERE pi.application_id = ? AND pd.del_flag = '0'", Integer.class, applicationId);
        boolean presidentDecided = presCnt != null && presCnt > 0;
        String presDecision = null;
        if (presidentDecided) {
            List<Map<String, Object>> pd = jdbcTemplate.queryForList(
                    "SELECT pd.decision FROM ccr_president_decision pd JOIN ccr_pricing_item pi ON pi.id = pd.pricing_item_id"
                            + " WHERE pi.application_id = ? AND pd.del_flag = '0' ORDER BY pd.decision_time DESC LIMIT 1",
                    applicationId);
            if (!pd.isEmpty()) {
                presDecision = String.valueOf(pd.get(0).get("decision"));
            }
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            String n = chain.get(i);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("nodeCode", n);
            node.put("label", NODE_LABEL.getOrDefault(n, n));
            if (RouteChains.SIX_PEOPLE_GROUP.equals(n)) {
                if (voteCounted) {
                    node.put("status", "DONE");
                    node.put("result", voteResult);
                } else if (round != null && "VOTING".equals(round.get("status"))) {
                    node.put("status", "CURRENT");
                    node.put("roundNo", round.get("roundNo"));
                    node.put("roundName", round.get("roundName"));
                    node.put("voterCount", round.get("voterCount"));
                    node.put("requiredCount", round.get("requiredCount"));
                    node.put("submittedCount", jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM ccr_vote_assignment WHERE round_id = ? AND status = 'SUBMITTED'",
                            Integer.class, round.get("id")));
                    // 实时同意票数仅行长/审计/超管可见(§用户拍板:委员互不知票,投票中不泄露票数)
                    if (isPrivilegedVoteViewer()) {
                        node.put("approveCount", jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM ccr_ballot WHERE round_id = ? AND vote_choice = 'APPROVE'",
                                Integer.class, round.get("id")));
                    }
                } else {
                    node.put("status", i == curIdx && anyRouting ? "CURRENT" : "PENDING");
                }
            } else if ("PRESIDENT".equals(n)) {
                if (presidentDecided) {
                    node.put("status", "DONE");
                    node.put("decision", presDecision);
                } else if (i == curIdx && anyRouting) {
                    node.put("status", "CURRENT");
                } else {
                    node.put("status", "PENDING");
                }
            } else if (handledNodes.contains(n)) {
                node.put("status", "DONE");
                Map<String, Object> last = lastByNode.get(n);
                if (last != null) {
                    node.put("operatorName", last.get("operatorName"));
                    node.put("operationTime", last.get("operationTime"));
                }
            } else if (i == curIdx && anyRouting) {
                node.put("status", "CURRENT");
            } else if (i < curIdx) {
                node.put("status", "SKIPPED");
            } else {
                node.put("status", "PENDING");
            }
            nodes.add(node);
        }
        result.put("nodes", nodes);
        return result;
    }

    /** 表决汇总特权角色(行长/审计/超管):审批进度页实时票数仅其可见;委员等其余角色不泄露(互不知票) */
    private boolean isPrivilegedVoteViewer() {
        try {
            String roleCode = appLoginUser.requireCurrentUser().getRoleCode();
            return AppLoginUser.ROLE_PRESIDENT.equals(roleCode)
                    || AppLoginUser.ROLE_AUDITOR.equals(roleCode)
                    || AppLoginUser.ROLE_ADMIN.equals(roleCode);
        } catch (Exception e) {
            // 未登录/角色解析异常:按非特权处理(不泄露票数)
            return false;
        }
    }

    /** 路由原因文案:routeReason 链路形态(静态) + currentReason 当前节点原因(动态),均为系统规则推导 */
    private Map<String, Object> buildReason(Map<String, Object> app, Map<String, Object> main,
                                            List<String> chain, String curNode) {
        String businessType = app.get("businessType") == null ? null : app.get("businessType").toString();
        String customerScope = app.get("customerScope") == null ? null : app.get("customerScope").toString();
        String routeCode = main.get("routeCode") == null ? null : main.get("routeCode").toString();
        BigDecimal amount = toDecimal(main.get("pricingAmount"));
        BigDecimal rate = toDecimal(main.get("requestedRate"));
        boolean isLoan = businessType != null && businessType.startsWith("LOAN");

        List<String> rrs = new ArrayList<>();
        if ("GROUP".equals(customerScope)) {
            rrs.add("集团客户业务，按集团口径走审批链");
        }
        rrs.add(isLoan
                ? "对公贷款标准审批链：支行行长→部门总经理→总行分管行长→六人小组"
                : "存款/保证金业务简化链：支行行长→六人小组");
        if (chain.contains("SECRETARY")) {
            rrs.add("申请金额¥" + fmt(amount) + "万元≥1000万元且申请利率" + fmt(rate) + "%<2.6%，"
                    + "触发贷审会秘书岗审核（分管行长后必经）");
        }
        if (chain.contains("PRESIDENT")) {
            rrs.add("终审为六人小组表决，链尾追加总行行长终审决策");
        }
        String last = chain.isEmpty() ? null : chain.get(chain.size() - 1);
        if (StrUtil.isNotBlank(routeCode) && routeCode.equals(last) && !"PRESIDENT".equals(last)) {
            rrs.add("审批链按终审岗位「" + FINAL_ROLE_LABEL.getOrDefault(last, last) + "」截断，由该岗位终审");
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("routeReason", String.join("；", rrs));
        r.put("currentReason", currentReason(chain, curNode, amount, rate));
        return r;
    }

    /** 当前节点原因(为什么走到当前这一步,仅路由规则原因) */
    private String currentReason(List<String> chain, String curNode, BigDecimal amount, BigDecimal rate) {
        if (curNode == null) {
            return "流程已发起，等待路由推进";
        }
        int idx = chain.indexOf(curNode);
        int total = chain.size();
        switch (curNode) {
            case "BRANCH_MANAGER":
                return "流程自支行发起，首站「支行行长」审批";
            case "DEPT_GENERAL_MANAGER":
                return "金额/利率超出支行行长权限，按权限矩阵上报「部门总经理」审批";
            case "VICE_PRESIDENT":
                return "按权限矩阵由部门总经理上送「总行分管行长」审批";
            case "SECRETARY":
                return "申请金额¥" + fmt(amount) + "万元≥1000万元且申请利率" + fmt(rate) + "%<2.6%，"
                        + "触发「贷审会秘书岗」审核";
            case "SIX_PEOPLE_GROUP":
                return "终审权限需「六人小组」集体表决（通过线≥4票）";
            case "PRESIDENT":
                return "六人小组表决通过后，由「总行行长」终审决策（同意/否决）";
            default:
                return "第" + (idx + 1) + "步/共" + total + "步，当前处于「" + NODE_LABEL.getOrDefault(curNode, curNode) + "」";
        }
    }

    private BigDecimal toDecimal(Object v) {
        if (v == null) {
            return null;
        }
        return v instanceof BigDecimal ? (BigDecimal) v
                : v instanceof Number ? new BigDecimal(v.toString())
                : StrUtil.isBlank(v.toString()) ? null : new BigDecimal(v.toString());
    }

    /** 金额/利率展示:去尾零 */
    private String fmt(BigDecimal v) {
        if (v == null) {
            return "";
        }
        return v.stripTrailingZeros().toPlainString();
    }
}
