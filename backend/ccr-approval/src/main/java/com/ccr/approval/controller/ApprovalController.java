package com.ccr.approval.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.ccr.application.domain.CcrPricingItem;
import com.ccr.application.service.ApplicationAccessService;
import com.ccr.application.support.AppLoginUser;
import com.ccr.approval.dto.ApprovalResult;
import com.ccr.approval.dto.AutoBackfillResult;
import com.ccr.approval.service.ApprovalService;
import com.ccr.approval.service.FlowMonitorService;
import com.ccr.approval.support.RouteChains;
import com.ccr.common.core.domain.R;
import com.ccr.common.core.util.ContributionMerger;
import com.ccr.common.core.util.OrgAchievementAssembler;
import com.ccr.common.core.util.RelatedCustomerResolver;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 普通审批接口(§13.2 审批接口)
 * 身份口径:操作人取 Sa-Token 登录人,不再接受 operatorId/nodeCode 身份类传参(nodeCode 仅作节点一致性校验);
 * approve/reject 必传 versionNo,可选 Idempotency-Key 头
 */
@RestController
@RequestMapping("/ccr/approval")
public class ApprovalController {

    @Resource
    private ApprovalService approvalService;

    @Resource
    private FlowMonitorService flowMonitorService;

    @Resource
    private ApplicationAccessService applicationAccessService;

    @Resource
    private AppLoginUser appLoginUser;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 审批进度(§用户要求,链路可视化):按申请聚合链路各节点流转状态,admin/申请人/审批人均可查看。
     * 每节点 status: DONE(已审,含操作人/时间)/ CURRENT(当前)/ SKIPPED(矩阵跳过未走)/ PENDING(待办);
     * 表决节点(SIX_PEOPLE_GROUP)额外返回应投/已投/通过线(匿名,不暴露具体票数);
     * 行长决策节点(PRESIDENT)按当前节点或已决策判定。
     */
    @GetMapping("/progress")
    public R<Map<String, Object>> progress(@RequestParam Long applicationId) {
        applicationAccessService.requireView(applicationId);
        return R.ok(flowMonitorService.buildNodes(applicationId));
    }

    /**
     * 流程监控(运行监控「流程监控」tab):在途审批流程整体监控,admin 专属。
     * 每条流程按申请聚合,展示当前走到哪一步(nodes 节点时间线)+ 为什么走当前节点(路由规则原因)。
     */
    @GetMapping("/flow-monitor")
    @SaCheckRole("admin")
    public R<Map<String, Object>> flowMonitor(@RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "10") int size,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) String businessType,
                                              @RequestParam(required = false) String applicationNo) {
        return R.ok(flowMonitorService.pageFlows(Math.max(page, 1), Math.max(Math.min(size, 50), 1),
                status, businessType, applicationNo));
    }

    /**
     * 审批详情:定价分项+申请+客户+融资+贡献度+担保+流程路由(routeChain)+资料校验结果(质量 PASS/WARN/BLOCK)
     * +拟达成贡献度(ccr_application_commitment)+流程轨迹;集团场景返回成员级明细。
     * 客户/融资/贡献度优先读提交时冻结快照(source=SNAPSHOT,含 snapshotInfo),无快照降级数仓(source=REALTIME);
     * 另含历史履约(tracking)与机构达成(orgPerformance)
     */
    // 整单交付改造(2026-08-29):详情入口改 applicationId(与 approve/reject 一致);
    // 锚定分项=批内第一个分项,作为整单代表返回 pricingItem(审批动作/决议/表决均按锚定分项落库,
    // 整单链/当前节点以申请单为准);旧前端按分项 id 直达不再支持,需用申请号入口
    @GetMapping("/{applicationId}/detail")
    public R<Map<String, Object>> detail(@PathVariable Long applicationId) {
        applicationAccessService.requireView(applicationId);
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT * FROM ccr_pricing_item WHERE application_id = ? AND del_flag = '0' ORDER BY id LIMIT 1", applicationId);
        if (items.isEmpty()) {
            throw new ServiceException(404, "申请无定价分项");
        }
        Map<String, Object> item = items.get(0);
        // 锚定分项 id:整单动作/决议/表决/担保明细均挂锚定分项,按它查即覆盖整单
        Long pricingItemId = ((Number) item.get("id")).longValue();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pricingItem", item);

        Long appId = applicationId;
        String custNo = item.get("pricing_customer_no") == null ? "" : item.get("pricing_customer_no").toString();
        String businessType = null;

        // 申请概要
        if (appId != null) {
            List<Map<String, Object>> apps = jdbcTemplate.queryForList(
                    "SELECT a.id, a.application_no applicationNo, a.business_type businessType, a.customer_no customerNo, a.group_no groupNo, a.application_remark applicationRemark, a.snapshot_bundle_id snapshotBundleId, a.customer_info_json customerInfoJson, a.credit_info_json creditInfoJson, a.submit_time submitTime, a.applicant_user_id applicantUserId, a.group_info_json groupInfoJson, a.applicant_org_id applicantOrgId, d.dept_name applicantOrgName,"
                            + " a.route_chain routeChain, a.current_node_code currentNodeCode, a.route_code routeCode, a.start_node_code startNodeCode, a.boundary_rate boundaryRate, a.matched_matrix_no matchedMatrixNo, a.version_no versionNo, a.status applicationStatus"
                            + " FROM ccr_application a LEFT JOIN ccr_sys_dept d ON d.id = a.applicant_org_id AND d.del_flag = '0' WHERE a.id = ?", appId);
            result.put("application", apps);
            if (!apps.isEmpty()) {
                businessType = apps.get(0).get("businessType") == null ? null : apps.get(0).get("businessType").toString();
                // 集团场景:本次涉及成员(成员客户号真实存在,成员级明细)
                Object groupNoObj = apps.get(0).get("groupNo");
                if (groupNoObj != null) {
                    String groupNo = groupNoObj.toString();
                    result.put("groupMembers", jdbcTemplate.queryForList(
                            "SELECT member_customer_no memberCustomerNo, member_role memberRole, request_amount requestAmount FROM ccr_application_member WHERE application_id = ? AND del_flag = '0'", appId));
                    // P1-2:集团贡献度(数仓 GROUP 口径 TOTAL 综合贡献总额,最新批次;集团号=group_no)
                    List<Map<String, Object>> gc = jdbcTemplate.queryForList(
                            "SELECT metric_value metricValue, value_type valueType FROM dw_contribution_metric"
                                    + " WHERE cust_no = ? AND metric_code = 'TOTAL' AND metric_scope = 'GROUP'"
                                    + " AND data_dt = (SELECT MAX(data_dt) FROM dw_contribution_metric"
                                    + " WHERE cust_no = ? AND metric_code = 'TOTAL' AND metric_scope = 'GROUP')",
                            groupNo, groupNo);
                    result.put("groupContribution", gc.isEmpty() ? List.of() : gc.get(0));
                }
                // 资料校验结果:快照质量 PASS/WARN/BLOCK 明细与整体结论
                Object bundleId = apps.get(0).get("snapshotBundleId");
                if (bundleId != null) {
                    List<Map<String, Object>> quality = jdbcTemplate.queryForList(
                            "SELECT rule_code ruleCode, rule_level ruleLevel, subject_type subjectType, subject_id subjectId, message, checked_time checkedTime FROM ccr_snapshot_quality_result WHERE bundle_id = ? AND del_flag = '0' ORDER BY rule_level DESC, rule_code", bundleId);
                    result.put("qualityResults", quality);
                    result.put("qualityOverall", quality.stream().anyMatch(q -> "BLOCK".equals(q.get("ruleLevel"))) ? "BLOCK"
                            : quality.stream().anyMatch(q -> "WARN".equals(q.get("ruleLevel"))) ? "WARN" : "PASS");
                }
            }
        }
        // 流程路由链(整单交付改造):优先申请单整单链(application.route_chain,贷款=利率最低分项定链),
        // 回退锚定分项冻结链,再回退固定链按 route_code 截断;route_code 同样以申请单为准
        Object routeCode = item.get("route_code");
        Object routeChainObj = item.get("route_chain");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> appRowData = (List<Map<String, Object>>) result.get("application");
        if (appRowData != null && !appRowData.isEmpty()) {
            Object appChain = appRowData.get(0).get("routeChain");
            if (appChain != null && StrUtil.isNotBlank(appChain.toString())) {
                routeChainObj = appChain;
            }
            Object appRouteCode = appRowData.get(0).get("routeCode");
            if (appRouteCode != null && StrUtil.isNotBlank(appRouteCode.toString())) {
                routeCode = appRouteCode;
            }
        }
        String routeCodeStr = routeCode == null ? null : routeCode.toString();
        List<String> routeChain;
        if (routeChainObj != null && StrUtil.isNotBlank(routeChainObj.toString())) {
            try {
                routeChain = JSONUtil.parseArray(routeChainObj.toString()).toList(String.class);
            } catch (Exception e) {
                routeChain = RouteChains.of(businessType, routeCodeStr);
            }
        } else {
            routeChain = RouteChains.of(businessType, routeCodeStr);
        }
        result.put("routeChain", routeChain);
        // 拟达成贡献度(申请承诺指标,按申请关联 §十三 13.2-6;成员级含成员客户号)
        if (appId != null) {
            result.put("commitments", jdbcTemplate.queryForList(
                    "SELECT metric_code metricCode, target_type targetType, baseline_value baselineValue, target_value targetValue, unit, metric_scope metricScope, member_customer_no memberCustomerNo, commitment_desc commitmentDesc, end_date endDate FROM ccr_application_commitment WHERE application_id = ? AND del_flag = '0' ORDER BY id", appId));
        } else {
            result.put("commitments", List.of());
        }
        // 流程轨迹(审批动作 + 客户经理提交起点):动作补处理人姓名;以申请提交时间合成首条 SUBMIT(从提交开始显示)
        Object submitTimeObj = null;
        Object applicantUserIdObj = null;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> appRows = (List<Map<String, Object>>) result.get("application");
        if (appRows != null && !appRows.isEmpty()) {
            submitTimeObj = appRows.get(0).get("submitTime");
            applicantUserIdObj = appRows.get(0).get("applicantUserId");
        }
        List<Map<String, Object>> trace = new ArrayList<>();
        if (submitTimeObj != null) {
            Map<String, Object> submit = new LinkedHashMap<>();
            submit.put("actionType", "SUBMIT");
            Object appStartNode = appRowData == null || appRowData.isEmpty() ? null : appRowData.get(0).get("startNodeCode");
            submit.put("nodeCode", appStartNode != null ? appStartNode : item.get("start_node_code"));
            submit.put("operatorId", applicantUserIdObj);
            submit.put("operatorName", nickName(applicantUserIdObj));
            submit.put("actionComment", "客户经理提交申请,进入审批流程");
            submit.put("beforeRate", null);
            submit.put("afterRate", null);
            submit.put("fromStatus", "DRAFT");
            submit.put("toStatus", "ROUTING");
            submit.put("operationTime", submitTimeObj);
            trace.add(submit);
        }
        trace.addAll(jdbcTemplate.queryForList(
                "SELECT a.action_type actionType, a.node_code nodeCode, a.operator_id operatorId, u.nick_name operatorName,"
                        + " a.action_comment actionComment, a.before_rate beforeRate, a.after_rate afterRate,"
                        + " a.from_status fromStatus, a.to_status toStatus, a.operation_time operationTime"
                        + " FROM ccr_approval_action a LEFT JOIN ccr_sys_user u ON u.id = a.operator_id"
                        + " WHERE a.pricing_item_id = ? AND a.del_flag = '0' ORDER BY a.operation_time", pricingItemId));
        result.put("flowTrace", trace);

        // 当前执行状态(整单交付改造):当前节点/状态优先申请单(application.current_node_code/status),
        // 回退锚定分项;到达当前节点时间取当前节点最早动作时间(首节点回退提交时间)
        Object appCurNode = appRowData == null || appRowData.isEmpty() ? null : appRowData.get(0).get("currentNodeCode");
        Object appCurStatus = appRowData == null || appRowData.isEmpty() ? null : appRowData.get(0).get("applicationStatus");
        result.put("currentNodeCode", appCurNode != null ? appCurNode : item.get("current_node_code"));
        result.put("currentStatus", appCurStatus != null ? appCurStatus : item.get("status"));
        String nodeReachTime = submitTimeObj == null ? null : submitTimeObj.toString();
        Object curNode = appCurNode != null ? appCurNode : item.get("current_node_code");
        if (curNode != null) {
            List<Map<String, Object>> reachRows = jdbcTemplate.queryForList(
                    "SELECT MIN(operation_time) reachTime FROM ccr_approval_action WHERE pricing_item_id = ? AND node_code = ? AND del_flag = '0'",
                    pricingItemId, curNode);
            if (!reachRows.isEmpty() && reachRows.get(0).get("reachTime") != null) {
                nodeReachTime = reachRows.get(0).get("reachTime").toString();
            }
        }
        result.put("nodeReachTime", nodeReachTime);

        // 客户信息/本行融资/当前贡献度:优先读提交时冻结快照(详设修正项③);无快照降级数仓并标注 source=REALTIME
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> appBrief = (List<Map<String, Object>>) result.get("application");
        Object bundleId = appBrief == null || appBrief.isEmpty() ? null : appBrief.get(0).get("snapshotBundleId");
        List<Map<String, Object>> snapshotRecords = bundleId == null ? List.of()
                : jdbcTemplate.queryForList(
                        "SELECT subject_type subjectType, subject_id subjectId, source_data_dt sourceDataDt, core_json coreJson"
                                + " FROM ccr_snapshot_record WHERE bundle_id = ? AND del_flag = '0'", bundleId);
        // 集团场景:customer 展示集团本身(集团名 + 集团补录对公要素),不按成员客户号查(集团申请 customer_no 为空)
        Map<String, Object> appRow0 = appBrief == null || appBrief.isEmpty() ? null : appBrief.get(0);
        Object groupNoObj = appRow0 == null ? null : appRow0.get("groupNo");
        boolean groupScene = groupNoObj != null && StrUtil.isNotBlank(groupNoObj.toString());
        String groupNoStr = groupScene ? groupNoObj.toString() : null;
        if (!snapshotRecords.isEmpty()) {
            result.put("source", "SNAPSHOT");
            result.put("customer", groupScene
                    ? groupCustomerOf(groupNoStr, snapshotRecords, appRow0)
                    : snapshotCustomer(snapshotRecords, custNo));
            result.put("financing", snapshotFinancing(snapshotRecords, custNo));
            result.put("contribution", snapshotContribution(snapshotRecords, custNo));
            result.put("snapshotInfo", snapshotInfo(bundleId, snapshotRecords));
        } else {
            // 降级:数仓实时查询
            result.put("source", "REALTIME");
            if (groupScene) {
                result.put("customer", groupCustomerOf(groupNoStr, List.of(), appRow0));
            } else {
                List<Map<String, Object>> corp = jdbcTemplate.queryForList(
                        "SELECT cust_no customerNo, cust_name customerName, cert_no certNo, entp_charic entpCharic, entp_scale entpScale,"
                                + " blgd_idsty industry, crdt_grd creditLevel, ffthlv_class fiveLevelClass, entp_empe_num empeNum,"
                                + " rest_asts totalAssets, estp_estb_dt estbDate, rest_addr restAddr, openact_org_nm openOrgName,"
                                + " openact_dt openDate, cust_class customerClass, 'CORP' custType"
                                + " FROM caps_corp_cust_basic_info WHERE cust_no = ? LIMIT 1", custNo);
                result.put("customer", corp.isEmpty()
                        ? jdbcTemplate.queryForList("SELECT cust_no customerNo, cust_nm customerName, cert_tp certType, cert_no certNo,"
                                + " gnd gender, ocupn occupation, whlyr_incm annualIncome, mrrg_sittn maritalStatus, rsd_addr address,"
                                + " tel_no phone, opnact_org_nm openOrgName, opnact_dt openDate, ffthlv_class fiveLevelClass,"
                                + " cust_class customerClass, 'INDIV' custType"
                                + " FROM caps_indv_cust_basic_info WHERE cust_no = ? LIMIT 1", custNo)
                        : corp);
            }
            result.put("financing", jdbcTemplate.queryForList(
                    "SELECT contract_no contractNo, agreement_no agreementNo, tranche_no trancheNo, borrower_customer_no borrowerCustomerNo,"
                    + " contract_amount contractAmount, contract_balance loanBalance, guarantee_type guaranteeType, currency,"
                    + " execution_rate contractRate, rate_type rateType, lpr_term lprTerm, start_date startDate,"
                    + " maturity_date maturityDate, contract_status contractStatus, contract_version contractVersion"
                    + " FROM dw_loan_contract_snapshot WHERE borrower_customer_no = ?", custNo));
            result.put("contribution", jdbcTemplate.queryForList(
                    "SELECT metric_code metricCode, metric_name metricName, metric_value metricValue, value_type valueType FROM dw_contribution_metric WHERE cust_no = ?", custNo));
        }
        // 集团成员名称补充:快照成员主数据/手工成员快照优先,降级实时数仓/手工成员表
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groupMemberRows = (List<Map<String, Object>>) result.get("groupMembers");
        if (groupMemberRows != null) {
            enrichMemberNames(groupMemberRows, snapshotRecords, appRow0);
        }

        // 客户信息人工修正(ccr_application.customer_info_json):数仓带出后人工调整,新增客户后台拉不出时手工填写;审批优先展示人工值
        Object ciObj = appBrief == null || appBrief.isEmpty() ? null : appBrief.get(0).get("customerInfoJson");
        if (ciObj != null && !ciObj.toString().isBlank()) {
            cn.hutool.json.JSONObject manual = JSONUtil.parseObj(ciObj.toString());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> custList = (List<Map<String, Object>>) result.get("customer");
            if (custList == null) {
                custList = new ArrayList<>();
            }
            // 新增客户纯人工录入:数仓无基线,快照为人工构建(data_source=MANUAL 标记),视为 manualOnly 展示 source=MANUAL
            boolean manualOnly = custList.isEmpty()
                    || "MANUAL".equals(custList.get(0).get("dataSource"));
            Map<String, Object> row = manualOnly ? new LinkedHashMap<>() : custList.get(0);
            // 客户号/名称:仅非空覆盖(名称必填,空值不抹除基线);其余字段含空覆盖(清空生效,以人工为准)
            overwriteCustomer(row, manual, "customerNo", "customerNo", false);
            overwriteCustomer(row, manual, "customerName", "customerName", false);
            // 证件号码:对私(个人)取 idNo,对公(企业单户)取统一社会信用代码 ucrCode(前端对私仅提交 idNo、对公仅提交 ucrCode)
            if ("INDV".equals(manual.getStr("custType"))) {
                overwriteCustomer(row, manual, "idNo", "certNo", true);
            } else {
                overwriteCustomer(row, manual, "ucrCode", "certNo", true);
            }
            overwriteCustomer(row, manual, "idType", "certType", true);
            overwriteCustomer(row, manual, "fiveLevelClass", "fiveLevelClass", true);
            overwriteCustomer(row, manual, "creditLevel", "creditLevel", true);
            overwriteCustomer(row, manual, "industry", "industry", true);
            overwriteCustomer(row, manual, "entpCharic", "entpCharic", true);
            overwriteCustomer(row, manual, "registeredCapital", "registeredCapital", true);
            overwriteCustomer(row, manual, "occupation", "occupation", true);
            overwriteCustomer(row, manual, "annualIncome", "annualIncome", true);
            overwriteCustomer(row, manual, "maritalStatus", "maritalStatus", true);
            overwriteCustomer(row, manual, "phone", "phone", true);
            overwriteCustomer(row, manual, "openOrg", "openOrgName", true);
            overwriteCustomer(row, manual, "openDate", "openDate", true);
            if (manualOnly) {
                // 新增客户:数仓/快照无基线,人工录入即唯一来源(row 可能来自人工构建快照,已在 custList,无需重复 add)
                row.put("custType", "CORP".equals(manual.getStr("custType")) ? "CORP" : "INDIV");
                row.put("source", "MANUAL");
                if (custList.isEmpty()) {
                    custList.add(row);
                }
                result.put("customer", custList);
                result.put("source", "MANUAL");
            } else {
                row.put("source", "MANUAL_OVERRIDE");
                result.put("source", "MANUAL_OVERRIDE");
            }
        }

        // 历史履约:该客户每次申请(有承诺计划)的总体履约比例 + 总额
        // 口径与承诺跟踪页「总体进度」一致:Σ实际 / Σ目标(仅数值指标);每指标取最新 data_dt
        List<Map<String, Object>> planRows = jdbcTemplate.queryForList("""
                SELECT cp.id planId, cp.plan_no planNo, cp.status planStatus,
                       a.application_no applicationNo, a.submit_time submitTime
                FROM ccr_commitment_plan cp
                JOIN ccr_resolution r ON r.id = cp.resolution_id
                LEFT JOIN ccr_pricing_item pi ON pi.id = r.pricing_item_id
                JOIN ccr_application a ON a.id = COALESCE(r.application_id, pi.application_id)
                WHERE cp.del_flag = '0' AND (cp.customer_no = ? OR cp.member_customer_no = ?)
                ORDER BY a.submit_time DESC
                """, custNo, custNo);
        List<Map<String, Object>> tracking = new ArrayList<>();
        for (Map<String, Object> p : planRows) {
            Object planId = p.get("planId");
            List<Map<String, Object>> metrics = jdbcTemplate.queryForList("""
                    SELECT cm.metric_code metricCode, cm.metric_name metricName, cm.target_value targetValue,
                           te.data_dt dataDt, te.actual_value actualValue, te.achievement_ratio achievementRatio,
                           te.result_status resultStatus
                    FROM ccr_tracking_evaluation te
                    JOIN (SELECT metric_id, MAX(data_dt) max_dt FROM ccr_tracking_evaluation WHERE plan_id = ? GROUP BY metric_id) t
                         ON t.metric_id = te.metric_id AND t.max_dt = te.data_dt
                    JOIN ccr_commitment_metric cm ON cm.id = te.metric_id
                    WHERE te.plan_id = ?
                    ORDER BY cm.metric_code
                    """, planId, planId);
            double sumActual = 0, sumTarget = 0;
            for (Map<String, Object> m : metrics) {
                if (m.get("actualValue") instanceof Number && m.get("targetValue") instanceof Number) {
                    sumActual += ((Number) m.get("actualValue")).doubleValue();
                    sumTarget += ((Number) m.get("targetValue")).doubleValue();
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("applicationNo", p.get("applicationNo"));
            row.put("submitTime", p.get("submitTime"));
            row.put("planNo", p.get("planNo"));
            row.put("planStatus", p.get("planStatus"));
            row.put("metrics", metrics);
            row.put("sumActual", Math.round(sumActual * 100.0) / 100.0);
            row.put("sumTarget", Math.round(sumTarget * 100.0) / 100.0);
            row.put("ratio", sumTarget > 0 ? Math.round(sumActual / sumTarget * 1000.0) / 10.0 : null);
            tracking.add(row);
        }
        result.put("tracking", tracking);

        // 机构达成:申请机构最新批次(dw_org_performance_snapshot)
        result.put("orgPerformance", orgPerformance(appId));

        // P1-2:合同下借据(数仓借据快照,最新批次;贷款场景按借款人客户号取本行借据)
        if (!"DEPOSIT".equals(businessType)) {
            result.put("loanNotes", jdbcTemplate.queryForList(
                    "SELECT contract_no contractNo, loan_note_no loanNoteNo, loan_balance loanBalance,"
                            + " execution_rate executionRate, rate_type rateType, lpr_term lprTerm,"
                            + " start_date startDate, maturity_date maturityDate, note_status noteStatus"
                            + " FROM dw_loan_note_snapshot WHERE borrower_customer_no = ?"
                            + " AND data_dt = (SELECT MAX(data_dt) FROM dw_loan_note_snapshot WHERE borrower_customer_no = ?)"
                            + " ORDER BY contract_no, loan_note_no", custNo, custNo));
        }

        // 存款分项:账户区块(§12.7 存款场景;快照优先,含拟开户标识与脱敏账号)
        if ("DEPOSIT".equals(businessType)) {
            result.put("depositAccounts", depositAccountView(pricingItemId, snapshotRecords));
        }

        // 担保分项(含措施扩展字段,审批端按担保类型完整展示申请录入内容)
        result.put("guarantees", jdbcTemplate.queryForList(
                "SELECT gp.main_guarantee_type guaranteeType, gp.package_version packageVersion, gm.measure_no measureNo, gm.measure_type measureType, gm.guarantee_amount guaranteeAmount, gm.ext_json extJson FROM ccr_guarantee_package gp LEFT JOIN ccr_guarantee_measure gm ON gm.package_id = gp.id WHERE gp.pricing_item_id = ? AND gp.del_flag = '0'", pricingItemId));

        // 同申请分项摘要(参照 demo 分项审批:逐分项同意/否决 + 一键通过/否决;agreed=本节点已同意待齐套,passed=任意节点已通过)
        if (appId != null) {
            List<Map<String, Object>> siblings = jdbcTemplate.queryForList(
                    "SELECT pi.id, pi.pricing_item_no pricingItemNo, pi.pricing_carrier_type carrierType, pi.pricing_amount pricingAmount,"
                            + " pi.requested_rate requestedRate, pi.current_approval_rate currentApprovalRate, pi.current_node_code currentNodeCode,"
                            + " pi.status, pi.version_no versionNo, pi.original_rate originalRate, pi.calculated_rate calculatedRate,"
                            + " pi.term_value termValue, pi.term_unit termUnit, pi.product_code productCode, pi.dept_code deptCode,"
                            + " pi.member_customer_no memberCustomerNo,"
                            + " pi.route_chain routeChain,"
                            + " rel.loan_contract_no contractNo"
                            + " FROM ccr_pricing_item pi"
                            + " LEFT JOIN ccr_pricing_item_contract_rel rel ON rel.pricing_item_id = pi.id AND rel.del_flag = '0'"
                            + " WHERE pi.application_id = ? AND pi.del_flag = '0' ORDER BY pi.id", appId);
            if (!siblings.isEmpty()) {
                StringBuilder inSb = new StringBuilder();
                for (Map<String, Object> s : siblings) {
                    if (inSb.length() > 0) {
                        inSb.append(',');
                    }
                    inSb.append(s.get("id"));
                }
                String nodeCode = item.get("current_node_code") == null ? "" : item.get("current_node_code").toString();
                Set<Object> agreed = new LinkedHashSet<>();
                Set<Object> rejected = new LinkedHashSet<>();
                Set<Object> passed = new LinkedHashSet<>();
                if (StrUtil.isNotBlank(nodeCode)) {
                    // 本节点已同意待齐套:ccr_approval_action 当前节点 APPROVE 记录覆盖的分项
                    List<Map<String, Object>> nodeApproves = jdbcTemplate.queryForList(
                            "SELECT DISTINCT pricing_item_id pricingItemId FROM ccr_approval_action"
                                    + " WHERE node_code = ? AND action_type = 'APPROVE' AND pricing_item_id IN (" + inSb + ") AND del_flag = '0'",
                            nodeCode);
                    for (Map<String, Object> a : nodeApproves) {
                        agreed.add(a.get("pricingItemId"));
                    }
                    // 本节点已否决待齐套(逐项否决模型 2026-08-27):ccr_approval_action 当前节点 REJECT 记录覆盖的分项;
                    // 已置 REJECTED 终态的分项由 status 判断(上级仅查看),不在本集合
                    List<Map<String, Object>> nodeRejects = jdbcTemplate.queryForList(
                            "SELECT DISTINCT pricing_item_id pricingItemId FROM ccr_approval_action"
                                    + " WHERE node_code = ? AND action_type = 'REJECT' AND pricing_item_id IN (" + inSb + ") AND del_flag = '0'",
                            nodeCode);
                    for (Map<String, Object> a : nodeRejects) {
                        rejected.add(a.get("pricingItemId"));
                    }
                }
                // 已通过集合:仅「流程已终态通过」的分项(逐项审批模型 2026-08-27 下,中间节点 APPROVE
                // 只是「本节点已同意待齐套」,不等于流程通过;若按任意节点 APPROVE 动作判定,支行行长逐项同意的
                // VOTING 分项会被误判 passed,小组委员的逐项同意/否决按钮被「仅展示」隐藏只剩一键审批)。
                // 上级仅查看的终态通过分项按 status 判定,REJECTED 终态分项由前端 status 单独展示。
                List<Map<String, Object>> terminalApproved = jdbcTemplate.queryForList(
                        "SELECT DISTINCT id pricingItemId FROM ccr_pricing_item"
                                + " WHERE application_id = ? AND status IN ('APPROVED_LEVEL','APPROVED_FINAL','APPROVED') AND del_flag = '0'", appId);
                for (Map<String, Object> a : terminalApproved) {
                    passed.add(a.get("pricingItemId"));
                }
                // 按分项挂担保(同申请全部分项批量查,审批决定区每分项行内嵌自己的担保明细)
                StringBuilder gInSb = new StringBuilder();
                for (Map<String, Object> s : siblings) {
                    if (gInSb.length() > 0) {
                        gInSb.append(',');
                    }
                    gInSb.append(s.get("id"));
                }
                Map<Object, List<Map<String, Object>>> guaranteeByItem = new HashMap<>();
                for (Map<String, Object> g : jdbcTemplate.queryForList(
                        "SELECT gp.pricing_item_id pricingItemId, gp.main_guarantee_type guaranteeType, gp.package_version packageVersion,"
                                + " gm.measure_no measureNo, gm.measure_type measureType, gm.guarantee_amount guaranteeAmount, gm.ext_json extJson"
                                + " FROM ccr_guarantee_package gp LEFT JOIN ccr_guarantee_measure gm ON gm.package_id = gp.id"
                                + " WHERE gp.pricing_item_id IN (" + gInSb + ") AND gp.del_flag = '0' ORDER BY gp.id")) {
                    guaranteeByItem.computeIfAbsent(g.get("pricingItemId"), k -> new ArrayList<>()).add(g);
                }
                // 逐分项调价来源(2026-09-02):最近一次调价动作(before≠after)的节点/操作人/时间,
                // 审批页「利率审批」调价提示定位具体节点用;flowTrace 仅锚定分项轨迹,多分项须按分项单独查
                Map<Object, Map<String, Object>> adjustByItem = new HashMap<>();
                for (Map<String, Object> a : jdbcTemplate.queryForList(
                        "SELECT a.pricing_item_id pricingItemId, a.node_code adjustNodeCode, u.nick_name adjustOperatorName, a.operation_time adjustTime"
                                + " FROM ccr_approval_action a LEFT JOIN ccr_sys_user u ON u.id = a.operator_id"
                                + " WHERE a.pricing_item_id IN (" + gInSb + ") AND a.del_flag = '0'"
                                + " AND a.before_rate IS NOT NULL AND a.after_rate IS NOT NULL AND a.before_rate != a.after_rate"
                                + " ORDER BY a.operation_time DESC")) {
                    adjustByItem.putIfAbsent(a.get("pricingItemId"), a);
                }
                for (Map<String, Object> s : siblings) {
                    s.put("agreed", agreed.contains(s.get("id")));
                    s.put("rejected", rejected.contains(s.get("id")));
                    s.put("passed", passed.contains(s.get("id")));
                    s.put("guarantees", guaranteeByItem.getOrDefault(s.get("id"), Collections.emptyList()));
                    Map<String, Object> adj = adjustByItem.get(s.get("id"));
                    if (adj != null) {
                        s.put("adjustNodeCode", adj.get("adjustNodeCode"));
                        s.put("adjustOperatorName", adj.get("adjustOperatorName"));
                        s.put("adjustTime", adj.get("adjustTime"));
                    }
                    // 六人小组节点:分项挂轮次时附加委员本人票状态(审批页内联同意/否决,一人一票)
                    if (RouteChains.SIX_PEOPLE_GROUP.equals(nodeCode)) {
                        attachMyBallot(s);
                    }
                }
            }
            result.put("siblingItems", siblings);
        } else {
            result.put("siblingItems", List.of());
        }

        // 集团综合授信(集团申请 customer_no 为空,creditAgreements 无数据;按集团号查 dw_group_credit_snapshot 最新批次,§12.7)
        if (groupScene) {
            result.put("groupCredit", jdbcTemplate.queryForList(
                    "SELECT approved_total_amount approvedTotalAmount, allocated_amount allocatedAmount, used_amount usedAmount,"
                            + " available_amount availableAmount, currency, credit_start creditStart, credit_end creditEnd,"
                            + " revolving_flag revolvingFlag, credit_status creditStatus"
                            + " FROM dw_group_credit_snapshot WHERE group_no = ?"
                            + " AND data_dt = (SELECT MAX(data_dt) FROM dw_group_credit_snapshot WHERE group_no = ?)",
                    groupNoStr, groupNoStr));
        }

        // 授信协议(§12.7:授信协议编号/类型/起止/额度/已用,数仓最新批次)
        result.put("creditAgreements", jdbcTemplate.queryForList(
                "SELECT agreement_no agreementNo, agreement_type agreementType, credit_amount creditAmount, used_amount usedAmount, available_amount availableAmount, currency, start_date startDate, end_date endDate, agreement_status agreementStatus FROM dw_credit_agreement_snapshot WHERE customer_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_credit_agreement_snapshot WHERE customer_no = ?) ORDER BY agreement_no", custNo, custNo));

        // 授信协议补录/修正快照(ccr_application.credit_info_json):存量=协议带出可修正,新增=手工补录(协议号可空);
        // 补录值优先展示(source=APPLICATION),与数仓协议按协议号去重(同号数仓行不再重复展示)
        Object creditInfoJson = appBrief == null || appBrief.isEmpty() ? null : appBrief.get(0).get("creditInfoJson");
        if (creditInfoJson != null && !creditInfoJson.toString().isBlank()) {
            cn.hutool.json.JSONObject ci = JSONUtil.parseObj(creditInfoJson.toString());
            Map<String, Object> manual = new LinkedHashMap<>();
            manual.put("agreementNo", ci.getStr("agreementNo"));
            manual.put("agreementType", ci.getStr("agreementType"));
            manual.put("creditAmount", jsonSafe(ci.get("creditAmount")));
            manual.put("usedAmount", jsonSafe(ci.get("usedAmount")));
            manual.put("availableAmount", jsonSafe(ci.get("availableAmount")));
            manual.put("currency", ci.getStr("currency"));
            manual.put("startDate", ci.getStr("startDate"));
            manual.put("endDate", ci.getStr("endDate"));
            manual.put("agreementStatus", ci.getStr("agreementStatus"));
            manual.put("source", "APPLICATION");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> agreements = (List<Map<String, Object>>) result.get("creditAgreements");
            List<Map<String, Object>> merged = new ArrayList<>();
            merged.add(manual);
            String manualNo = manual.get("agreementNo") == null ? null : manual.get("agreementNo").toString();
            for (Map<String, Object> row : agreements) {
                Object no = row.get("agreementNo");
                String rowNo = no == null ? null : no.toString();
                // 补录协议号为空(新增授信手工补录)时不去重,直接保留数仓协议行,避免 manualNo 为 null 触发 NPE
                if (manualNo == null || manualNo.isEmpty()) {
                    merged.add(row);
                } else if (!manualNo.equals(rowNo)) {
                    merged.add(row);
                }
            }
            result.put("creditAgreements", merged);
        }

        // 他行融资概要(数仓征信最新批次;概要与授信协议/客户信息一致,补录快照优先)
        List<Map<String, Object>> otherLoanSummaryRows = new ArrayList<>();
        if (appId != null) {
            // 申请页「他行融资概要」随单持久化的补录快照(ccr_application_credit_summary,del_flag=0 取最新;数仓无征信时也能展示申请录入概要)
            List<Map<String, Object>> snapshotSummary = jdbcTemplate.queryForList(
                    "SELECT lender_count lenderCount, credit_amount_total creditAmountTotal, used_amount_total usedAmountTotal, loan_account_count loanAccountCount, overdue_account_count overdueAccountCount, overdue_balance overdueBalance, npl_balance nplBalance, special_mention_balance specialMentionBalance, external_guarantee_balance externalGuaranteeBalance, report_date reportDate"
                            + " FROM ccr_application_credit_summary WHERE application_id = ? AND del_flag = '0' ORDER BY id DESC LIMIT 1", appId);
            if (!snapshotSummary.isEmpty()) {
                otherLoanSummaryRows.addAll(snapshotSummary);
            }
        }
        // 数仓征信概要(同字段集;已有补录快照时作回退,不重复展示;报告日期=数仓征信报告日期,§2026-08-26)
        List<Map<String, Object>> dwSummary = jdbcTemplate.queryForList(
                "SELECT f.lender_count lenderCount, f.npl_balance nplBalance, f.credit_amount_total creditAmountTotal, f.used_amount_total usedAmountTotal, f.loan_account_count loanAccountCount, f.overdue_account_count overdueAccountCount, f.overdue_balance overdueBalance, f.special_mention_balance specialMentionBalance, f.external_guarantee_balance externalGuaranteeBalance, (SELECT r.report_date FROM dw_credit_report_snapshot r WHERE r.cust_no = f.cust_no ORDER BY r.data_dt DESC, r.report_date DESC LIMIT 1) reportDate FROM dw_credit_financing_summary f WHERE f.cust_no = ? ORDER BY f.data_dt DESC LIMIT 1", custNo);
        if (otherLoanSummaryRows.isEmpty() && !dwSummary.isEmpty()) {
            otherLoanSummaryRows.addAll(dwSummary);
        }
        result.put("otherLoanSummary", otherLoanSummaryRows);
        result.put("otherLoans", jdbcTemplate.queryForList(
                "SELECT lender_name lenderName, credit_amount creditAmount, used_amount usedAmount, balance_amount balanceAmount, annual_rate annualRate, data_dt dataDt, 'DW' inputMode FROM dw_credit_financing_detail WHERE customer_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_credit_financing_detail WHERE customer_no = ?)", custNo, custNo));

        // 申请附件(材料附件步骤上传;元数据,下载走 /ccr/applications/{appId}/attachments/{id}/download)
        if (appId != null) {
            result.put("attachments", jdbcTemplate.queryForList(
                    "SELECT id, file_name fileName, file_size fileSize, create_time createTime FROM ccr_application_attachment WHERE application_id = ? AND del_flag = '0' ORDER BY id", appId));
            result.put("appOtherLoans", jdbcTemplate.queryForList(
                    "SELECT lender_name lenderName, credit_amount creditAmount, used_amount usedAmount, balance_amount balanceAmount, annual_rate annualRate, input_mode inputMode FROM ccr_application_other_loan WHERE application_id = ? AND del_flag = '0' ORDER BY id", appId));
        }

        // 关联人(客户经理申请时实际录入,§12.4④;按关联客户号补全基本信息/授信信息)
        if (appId != null) {
            List<Map<String, Object>> relatedPersons = jdbcTemplate.queryForList(
                    "SELECT person_name personName, cert_no certNo, cert_type certType, relation_type relationType, related_customer_no relatedCustomerNo FROM ccr_application_related_person WHERE application_id = ? AND del_flag = '0' ORDER BY id", appId);
            enrichRelated(relatedPersons);
            result.put("relatedPersons", relatedPersons);
        }

        // 关联人贡献度归并:仅前台录入关联人(ccr_application_related_person)同 metric_code 值加总进主客户贡献度(§关联人贡献度归并)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contributionRows = (List<Map<String, Object>>) result.get("contribution");
        if (contributionRows != null) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> relPersons = (List<Map<String, Object>>) result.get("relatedPersons");
            if (relPersons == null) {
                relPersons = List.of();
            }
            // 空客户号关联人按证件号兜底反查数仓主数据补全(展示与归并共用该列表)
            RelatedCustomerResolver.resolveBatch(jdbcTemplate, relPersons);
            Set<String> relatedCustomerNos = new LinkedHashSet<>();
            for (Map<String, Object> rp : relPersons) {
                Object no = rp.get("relatedCustomerNo");
                if (no != null && !no.toString().isBlank()) {
                    relatedCustomerNos.add(no.toString());
                }
            }
            ContributionMerger.mergeRelatedContributions(jdbcTemplate, contributionRows, relatedCustomerNos);
        }

        // 决议与执行核验(§12.7 ⑪:审批终态后签发;决议日期=issue_time,无有效期周期)
        // 整单化后决议按申请维度一份(application_id 有值、pricing_item_id 为 NULL,2026-08-29 起),
        // 旧数据逐分项签发(application_id 为 NULL、pricing_item_id 有值,2026-08-28 及之前);
        // 故按 application_id 或 pricing_item_id 双键兼容查询(2026-09-02 修复决议书不显示)
        result.put("resolutions", jdbcTemplate.queryForList(
                "SELECT r.id, r.resolution_no resolutionNo, r.application_id applicationId, r.pricing_item_id pricingItemId, r.final_rate finalRate,"
                        + " r.effective_from effectiveFrom, r.effective_to effectiveTo, r.decision_source decisionSource,"
                        + " r.status, r.issue_time issueTime"
                        + " FROM ccr_resolution r WHERE (r.application_id = ? OR r.pricing_item_id = ?) AND r.del_flag = '0' ORDER BY r.issue_time", applicationId, pricingItemId));
        result.put("resolutionExecutions", jdbcTemplate.queryForList(
                "SELECT re.resolution_id resolutionId, re.loan_contract_no loanContractNo, re.supplement_agreement_no supplementAgreementNo,"
                        + " re.execution_rate executionRate, re.execution_status executionStatus, re.reconcile_result reconcileResult, re.reconcile_time reconcileTime"
                        + " FROM ccr_resolution_execution re JOIN ccr_resolution r ON r.id = re.resolution_id"
                        + " WHERE (r.application_id = ? OR r.pricing_item_id = ?) AND re.del_flag = '0'", applicationId, pricingItemId));

        // 六人小组节点:返回当前表决轮次匿名汇总 + 登录人本人票(审批页内联同意/否决 + 链路进度)
        result.put("voteRound", buildVoteRound(pricingItemId));

        // 表决汇总(行长/审计/超管可见,§12.7/T4-02/T4-10):按申请返回轮次/分项计票/行长决策结果,
        // 供行长决策页展示六人表决结果并按轮次加载匿名审批意见;其余角色不返回,保持委员匿名。
        // 与 ApprovalServiceImpl(历史/档案详情)三字段口径一致:voteRounds 轮次列表 + voteResults 分项计票 + presidentDecisions 行长决策。
        try {
            String roleCode = appLoginUser.requireCurrentUser().getRoleCode();
            if (AppLoginUser.ROLE_PRESIDENT.equals(roleCode)
                    || AppLoginUser.ROLE_AUDITOR.equals(roleCode)
                    || AppLoginUser.ROLE_ADMIN.equals(roleCode)) {
                result.put("voteRounds", jdbcTemplate.queryForList(
                        "SELECT id, round_no roundNo, round_name roundName, status,"
                                + " voter_count voterCount, required_count requiredCount,"
                                + " round_start_time roundStartTime, round_end_time roundEndTime"
                                + " FROM ccr_vote_round WHERE application_id = ? AND del_flag = '0' ORDER BY round_no",
                        appId));
                result.put("voteResults", jdbcTemplate.queryForList(
                        "SELECT vr.round_id roundId, vr.pricing_item_id pricingItemId,"
                                + " vr.approve_count approveCount, vr.reject_count rejectCount,"
                                + " vr.required_count requiredCount, vr.submitted_count submittedCount,"
                                + " vr.result, vr.count_time countTime"
                                + " FROM ccr_vote_result vr JOIN ccr_pricing_item pi ON pi.id = vr.pricing_item_id"
                                + " WHERE pi.application_id = ? AND vr.del_flag = '0'",
                        appId));
                result.put("presidentDecisions", jdbcTemplate.queryForList(
                        "SELECT pd.pricing_item_id pricingItemId, pd.decision, pd.opinion, pd.decision_time decisionTime"
                                + " FROM ccr_president_decision pd JOIN ccr_pricing_item pi ON pi.id = pd.pricing_item_id"
                                + " WHERE pi.application_id = ? AND pd.del_flag = '0'",
                        appId));
            }
        } catch (Exception ignored) {
            // 未登录/角色解析异常:不返回表决汇总,不阻断详情
        }
        return R.ok(result);
    }

    /**
     * 小组节点当前表决轮次匿名汇总 + 登录人本人票(§六人小组内联审批)。
     * 返回: roundId/roundStatus/roundName + submittedCount/voterCount/requiredCount/approveCount/rejectCount(匿名)
     * + myChoice/myComment/submitted(仅本人);非小组节点或无轮次返回 null。
     */
    private Map<String, Object> buildVoteRound(Long pricingItemId) {
        List<Map<String, Object>> rounds = jdbcTemplate.queryForList(
                "SELECT r.id roundId, r.round_name roundName, r.status roundStatus,"
                        + " r.voter_count voterCount, r.required_count requiredCount"
                        + " FROM ccr_vote_round r JOIN ccr_vote_round_item ri ON ri.round_id = r.id"
                        + " WHERE ri.pricing_item_id = ? AND r.del_flag = '0' ORDER BY r.id DESC LIMIT 1",
                pricingItemId);
        if (rounds.isEmpty()) {
            return null;
        }
        Map<String, Object> round = rounds.get(0);
        Number roundId = (Number) round.get("roundId");
        int submittedCount = count("SELECT COUNT(*) FROM ccr_ballot"
                + " WHERE round_id = ? AND pricing_item_id = ?", roundId, pricingItemId);
        int approveCount = count("SELECT COUNT(*) FROM ccr_ballot"
                + " WHERE round_id = ? AND pricing_item_id = ? AND vote_choice = 'APPROVE'", roundId, pricingItemId);
        int rejectCount = count("SELECT COUNT(*) FROM ccr_ballot"
                + " WHERE round_id = ? AND pricing_item_id = ? AND vote_choice = 'REJECT'", roundId, pricingItemId);
        Map<String, Object> voteRound = new LinkedHashMap<>(round);
        voteRound.put("submittedCount", submittedCount);
        // 匿名口径(§用户拍板):票数按"角色+批次状态"双条件可见——
        // 行长/审计/超管 或 批次已关闭(PASSED/FAILED)返回完整票数(全员投完后的计票统计);
        // 其余角色批次进行中(VOTING)仅返回进度,不泄露实时票数(委员互不知票)
        boolean roundClosed = "PASSED".equals(round.get("roundStatus"))
                || "FAILED".equals(round.get("roundStatus"));
        if (roundClosed || isPrivilegedVoteViewer()) {
            voteRound.put("approveCount", approveCount);
            voteRound.put("rejectCount", rejectCount);
        } else {
            voteRound.put("approveCount", null);
            voteRound.put("rejectCount", null);
        }
        // 登录人本人票(匿名口径:仅本人可见,不泄露他人)
        try {
            Long loginUserId = appLoginUser.requireLoginId();
            List<Map<String, Object>> mine = jdbcTemplate.queryForList(
                    "SELECT vote_choice myChoice, vote_comment myComment FROM ccr_ballot"
                            + " WHERE round_id = ? AND pricing_item_id = ? AND voter_user_hash = ?",
                    roundId, pricingItemId, DigestUtil.sha256Hex(String.valueOf(loginUserId)));
            if (!mine.isEmpty()) {
                voteRound.put("myChoice", mine.get(0).get("myChoice"));
                voteRound.put("myComment", mine.get(0).get("myComment"));
            }
        } catch (Exception e) {
            // 未登录/登录异常:仅返回匿名汇总,不阻断详情
        }
        return voteRound;
    }

    /** 表决汇总特权角色(行长/审计/超管):投票进行中即见实时票数;其余角色待批次关闭后才见最终计票统计 */
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

    /** 简单 COUNT 查询(返回 0 而非 null) */
    private int count(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    /**
     * 委员本人票附加(六人小组内联审批):sibling 挂在进行中轮次时,
     * 按登录人 hash 查本人票型(myChoice),已投分项前端显示"已投:同意/反对"并禁用。
     */
    private void attachMyBallot(Map<String, Object> sibling) {
        try {
            List<Map<String, Object>> rounds = jdbcTemplate.queryForList(
                    "SELECT r.id roundId, r.status roundStatus FROM ccr_vote_round r"
                            + " JOIN ccr_vote_round_item ri ON ri.round_id = r.id"
                            + " WHERE ri.pricing_item_id = ? AND r.del_flag = '0' ORDER BY r.id DESC LIMIT 1",
                    sibling.get("id"));
            if (rounds.isEmpty()) {
                return;
            }
            Object roundId = rounds.get(0).get("roundId");
            sibling.put("roundId", roundId);
            Long loginUserId = appLoginUser.requireLoginId();
            List<Map<String, Object>> mine = jdbcTemplate.queryForList(
                    "SELECT vote_choice myChoice, vote_comment myComment FROM ccr_ballot"
                            + " WHERE round_id = ? AND pricing_item_id = ? AND voter_user_hash = ?",
                    roundId, sibling.get("id"), DigestUtil.sha256Hex(String.valueOf(loginUserId)));
            if (!mine.isEmpty()) {
                sibling.put("myChoice", mine.get(0).get("myChoice"));
                sibling.put("myComment", mine.get(0).get("myComment"));
            }
        } catch (Exception e) {
            // 未登录/登录异常:仅返回匿名汇总,不阻断详情
        }
    }

    /** 关联人信息补全(§12.4④):按 relatedCustomerNo 批量反查基本信息(caps_corp/indv)+授信信息(授信协议数/本行贷款余额) */
    /** 用户昵称(流程轨迹处理人姓名;null 安全) */
    private String nickName(Object userId) {
        if (userId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT nick_name nickName FROM ccr_sys_user WHERE id = ?", userId);
        return rows.isEmpty() || rows.get(0).get("nickName") == null
                ? null : rows.get(0).get("nickName").toString();
    }

    private void enrichRelated(List<Map<String, Object>> persons) {
        if (persons == null || persons.isEmpty()) {
            return;
        }
        Set<String> customerNos = new LinkedHashSet<>();
        for (Map<String, Object> p : persons) {
            Object no = p.get("relatedCustomerNo");
            if (no != null && StrUtil.isNotBlank(no.toString())) {
                customerNos.add(no.toString());
            }
        }
        if (customerNos.isEmpty()) {
            return;
        }
        String in = String.join(",", Collections.nCopies(customerNos.size(), "?"));
        Object[] args = customerNos.toArray();

        // 基本信息:对私优先填充、对公覆盖(与快照客户 CORP 优先口径一致)
        Map<String, Map<String, Object>> basics = new HashMap<>();
        for (Map<String, Object> iv : jdbcTemplate.queryForList(
                "SELECT cust_no custNo, ocupn occupation, whlyr_incm annualIncome FROM caps_indv_cust_basic_info"
                        + " WHERE cust_no IN (" + in + ") AND data_dt = (SELECT MAX(d2.data_dt) FROM caps_indv_cust_basic_info d2 WHERE d2.cust_no = caps_indv_cust_basic_info.cust_no)", args)) {
            basics.put(String.valueOf(iv.get("custNo")), iv);
        }
        for (Map<String, Object> c : jdbcTemplate.queryForList(
                "SELECT cust_no custNo, entp_charic entpCharic, entp_scale entpScale, blgd_idsty industry, crdt_grd creditLevel, ffthlv_class fiveLevelClass"
                        + " FROM caps_corp_cust_basic_info WHERE cust_no IN (" + in + ") AND data_dt = (SELECT MAX(d2.data_dt) FROM caps_corp_cust_basic_info d2 WHERE d2.cust_no = caps_corp_cust_basic_info.cust_no)", args)) {
            basics.put(String.valueOf(c.get("custNo")), c);
        }

        // 授信信息:授信协议数 + 本行贷款余额合计(万元)
        Map<String, Object> agreementCounts = new HashMap<>();
        for (Map<String, Object> a : jdbcTemplate.queryForList(
                "SELECT customer_no customerNo, COUNT(*) cnt FROM dw_credit_agreement_snapshot"
                        + " WHERE customer_no IN (" + in + ") AND data_dt = (SELECT MAX(data_dt) FROM dw_credit_agreement_snapshot)"
                        + " GROUP BY customer_no", args)) {
            agreementCounts.put(String.valueOf(a.get("customerNo")), a.get("cnt"));
        }
        Map<String, Object> loanBalances = new HashMap<>();
        for (Map<String, Object> l : jdbcTemplate.queryForList(
                "SELECT borrower_customer_no customerNo, SUM(contract_balance) balance FROM dw_loan_contract_snapshot"
                        + " WHERE borrower_customer_no IN (" + in + ") AND data_dt = (SELECT MAX(data_dt) FROM dw_loan_contract_snapshot)"
                        + " GROUP BY borrower_customer_no", args)) {
            loanBalances.put(String.valueOf(l.get("customerNo")), l.get("balance"));
        }

        for (Map<String, Object> p : persons) {
            Object no = p.get("relatedCustomerNo");
            if (no == null) {
                continue;
            }
            Map<String, Object> basic = basics.get(no.toString());
            if (basic != null) {
                boolean corp = basic.containsKey("entpCharic");
                p.put("custType", corp ? "CORP" : "INDIV");
                p.put("entpCharic", basic.get("entpCharic"));
                p.put("entpScale", basic.get("entpScale"));
                p.put("industry", basic.get("industry"));
                p.put("creditLevel", basic.get("creditLevel"));
                p.put("fiveLevelClass", basic.get("fiveLevelClass"));
                p.put("occupation", basic.get("occupation"));
                p.put("annualIncome", basic.get("annualIncome"));
            }
            p.put("creditAgreementCount", agreementCounts.get(no.toString()));
            p.put("loanBalanceTotal", loanBalances.get(no.toString()));
        }
    }

    /**
     * 人工客户信息覆盖:仅当 manual JSON 中存在该键且值非 null 时写入目标;
     * allowBlank=false 时空值不覆盖(客户号/名称必填),true 时含空覆盖(清空生效,以人工填写为准)
     */
    private void overwriteCustomer(Map<String, Object> row, cn.hutool.json.JSONObject manual, String srcKey, String targetKey, boolean allowBlank) {
        Object raw = manual == null ? null : jsonSafe(manual.get(srcKey));
        if (raw == null) {
            return;
        }
        String v = raw.toString();
        if (!allowBlank && v.isBlank()) {
            return;
        }
        row.put(targetKey, v);
    }

    /** 快照内客户主数据(CORPORATE 对公优先,无则 INDIVIDUAL 对私),输出别名与实时口径一致 */
    private List<Map<String, Object>> snapshotCustomer(List<Map<String, Object>> records, String custNo) {
        Map<String, Object> corp = null;
        Map<String, Object> indv = null;
        for (Map<String, Object> record : records) {
            if (!custNo.equals(record.get("subjectId"))) {
                continue;
            }
            if ("CORPORATE".equals(record.get("subjectType"))) {
                corp = coreOf(record);
            } else if ("INDIVIDUAL".equals(record.get("subjectType"))) {
                indv = coreOf(record);
            }
        }
        Map<String, Object> row = new LinkedHashMap<>();
        if (corp != null) {
            // 对公客户基本信息(审批详情 §7.4/§14.2 ①,字段与实时降级口径一致)
            row.put("customerNo", jsonSafe(corp.get("cust_no")));
            row.put("customerName", jsonSafe(corp.get("cust_name")));
            row.put("certNo", jsonSafe(corp.get("cert_no")));
            row.put("entpCharic", jsonSafe(corp.get("entp_charic")));
            row.put("entpScale", jsonSafe(corp.get("entp_scale")));
            row.put("industry", jsonSafe(corp.get("blgd_idsty")));
            row.put("creditLevel", jsonSafe(corp.get("crdt_grd")));
            row.put("fiveLevelClass", jsonSafe(corp.get("ffthlv_class")));
            row.put("empeNum", jsonSafe(corp.get("entp_empe_num")));
            row.put("totalAssets", jsonSafe(corp.get("rest_asts")));
            row.put("estbDate", jsonSafe(corp.get("estp_estb_dt")));
            row.put("restAddr", jsonSafe(corp.get("rest_addr")));
            row.put("openOrgName", jsonSafe(corp.get("openact_org_nm")));
            row.put("openDate", jsonSafe(corp.get("openact_dt")));
            row.put("customerClass", jsonSafe(corp.get("cust_class")));
            row.put("custType", "CORP");
            row.put("dataSource", jsonSafe(corp.get("data_source")));
            return List.of(row);
        }
        if (indv != null) {
            // 对私客户基本信息(证件/职业/年收入/婚姻/居住/联系电话等)
            row.put("customerNo", jsonSafe(indv.get("cust_no")));
            row.put("customerName", jsonSafe(indv.get("cust_nm")));
            row.put("certType", jsonSafe(indv.get("cert_tp")));
            row.put("certNo", jsonSafe(indv.get("cert_no")));
            row.put("gender", jsonSafe(indv.get("gnd")));
            row.put("occupation", jsonSafe(indv.get("ocupn")));
            row.put("annualIncome", jsonSafe(indv.get("whlyr_incm")));
            row.put("maritalStatus", jsonSafe(indv.get("mrrg_sittn")));
            row.put("address", jsonSafe(indv.get("rsd_addr")));
            row.put("phone", jsonSafe(indv.get("tel_no")));
            row.put("openOrgName", jsonSafe(indv.get("opnact_org_nm")));
            row.put("openDate", jsonSafe(indv.get("opnact_dt")));
            row.put("fiveLevelClass", jsonSafe(indv.get("ffthlv_class")));
            row.put("customerClass", jsonSafe(indv.get("cust_class")));
            row.put("dataSource", jsonSafe(indv.get("data_source")));
            row.put("custType", "INDIV");
            return List.of(row);
        }
        return List.of();
    }

    /** 快照内本行融资(FINANCING 记录,按 cust_no 过滤) */
    private List<Map<String, Object>> snapshotFinancing(List<Map<String, Object>> records, String custNo) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> record : records) {
            // 提交快照以 CONTRACT(贷款合同)冻结本行融资;兼容早期 FINANCING 命名
            Object subjectType = record.get("subjectType");
            if (!"FINANCING".equals(subjectType) && !"CONTRACT".equals(subjectType)) {
                continue;
            }
            Map<String, Object> core = coreOf(record);
            // 客户过滤:贷款合同快照用 borrower_customer_no,兼容旧 FINANCING 用 cust_no
            Object coreCust = jsonSafe(core.get("borrower_customer_no"));
            if (coreCust == null) {
                coreCust = jsonSafe(core.get("cust_no"));
            }
            if (!custNo.equals(coreCust)) {
                continue;
            }
            Object loanBalance = jsonSafe(core.get("loan_balance"));
            if (loanBalance == null) {
                loanBalance = jsonSafe(core.get("contract_balance"));
            }
            Object contractRate = jsonSafe(core.get("contract_rate"));
            if (contractRate == null) {
                contractRate = jsonSafe(core.get("execution_rate"));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("contractNo", jsonSafe(core.get("contract_no")));
            row.put("agreementNo", jsonSafe(core.get("agreement_no")));
            row.put("trancheNo", jsonSafe(core.get("tranche_no")));
            row.put("contractAmount", jsonSafe(core.get("contract_amount")));
            row.put("loanBalance", loanBalance);
            row.put("contractRate", contractRate);
            row.put("rateType", jsonSafe(core.get("rate_type")));
            row.put("lprTerm", jsonSafe(core.get("lpr_term")));
            row.put("startDate", jsonSafe(core.get("start_date")));
            row.put("maturityDate", jsonSafe(core.get("maturity_date")));
            row.put("contractStatus", jsonSafe(core.get("contract_status")));
            row.put("currency", jsonSafe(core.get("currency")));
            row.put("guaranteeType", jsonSafe(core.get("guarantee_type")));
            rows.add(row);
        }
        return rows;
    }

    /** Hutool JSON 解析 null 值为 JSONNull 包装对象,Jackson 无法序列化,统一转 Java null */
    private static Object jsonSafe(Object v) {
        return (v instanceof cn.hutool.json.JSONNull) ? null : v;
    }

    /** 快照内贡献度(CONTRIBUTION 记录 core_json.metrics,提交时同客户同批次合并采集) */
    private List<Map<String, Object>> snapshotContribution(List<Map<String, Object>> records, String custNo) {
        for (Map<String, Object> record : records) {
            if (!"CONTRIBUTION".equals(record.get("subjectType")) || !custNo.equals(record.get("subjectId"))) {
                continue;
            }
            Object metrics = coreOf(record).get("metrics");
            if (!(metrics instanceof List<?> list)) {
                return List.of();
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object m : list) {
                if (!(m instanceof Map<?, ?> metric)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("metricCode", jsonSafe(metric.get("metric_code")));
                row.put("metricName", jsonSafe(metric.get("metric_name")));
                row.put("metricValue", jsonSafe(metric.get("metric_value")));
                row.put("valueType", jsonSafe(metric.get("value_type")));
                rows.add(row);
            }
            return rows;
        }
        return List.of();
    }

    /** 快照信息(§12.16-7):bundle_no/freeze_time/数据日期(记录最大 source_data_dt) */
    private Map<String, Object> snapshotInfo(Object bundleId, List<Map<String, Object>> records) {
        Map<String, Object> info = new LinkedHashMap<>();
        List<Map<String, Object>> bundles = jdbcTemplate.queryForList(
                "SELECT bundle_no bundleNo, freeze_time freezeTime FROM ccr_snapshot_bundle WHERE id = ?", bundleId);
        if (!bundles.isEmpty()) {
            info.put("bundleNo", bundles.get(0).get("bundleNo"));
            info.put("freezeTime", bundles.get(0).get("freezeTime"));
        }
        records.stream().map(r -> r.get("sourceDataDt")).filter(Objects::nonNull)
                .map(Object::toString).max(String::compareTo)
                .ifPresent(dt -> info.put("dataDt", dt));
        return info;
    }

    /** 机构达成(§12.16):申请机构 → ccr_sys_dept.org_code → 本系统实时组装(增量021 B方案,废弃数仓 dw_org_performance_snapshot) */
    private List<Map<String, Object>> orgPerformance(Long appId) {
        if (appId == null) {
            return List.of();
        }
        List<Map<String, Object>> orgCodes = jdbcTemplate.queryForList(
                "SELECT d.org_code orgCode FROM ccr_application a JOIN ccr_sys_dept d ON d.id = a.applicant_org_id"
                        + " WHERE a.id = ? AND d.del_flag = '0'", appId);
        if (orgCodes.isEmpty() || orgCodes.get(0).get("orgCode") == null) {
            return List.of();
        }
        return OrgAchievementAssembler.assemble(jdbcTemplate, orgCodes.get(0).get("orgCode").toString());
    }

    /** 解析快照 core_json(JSON 列查询结果为字符串) */
    @SuppressWarnings("unchecked")
    private Map<String, Object> coreOf(Map<String, Object> record) {
        Object coreJson = record.get("coreJson");
        if (coreJson == null) {
            return Map.of();
        }
        if (coreJson instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return JSONUtil.parseObj(coreJson.toString());
    }

    /**
     * 集团场景客户信息行:集团申请 customer_no 为空,客户信息展示集团本身。
     * 数据源优先级:提交快照 GROUP 记录(集团主数据 core_json,含 group_name/group_type/group_status)
     * → 数仓实时集团主数据 → 申请上下文 group_info_json(新增集团补录;补录对公要素仅此来源) → 集团号兜底。
     * 键名对齐前端对公模板(customerName/certNo/fiveLevelClass/creditLevel/industry/registeredCapital/openOrgName/openDate/basicAccount)。
     */
    private List<Map<String, Object>> groupCustomerOf(String groupNo, List<Map<String, Object>> snapshotRecords,
                                                      Map<String, Object> application) {
        String groupName = null, groupType = null, groupStatus = null, groupStateOwned = null, groupUcrCode = null;
        for (Map<String, Object> record : snapshotRecords) {
            if (!groupNo.equals(record.get("subjectId")) || !"GROUP".equals(record.get("subjectType"))) {
                continue;
            }
            Map<String, Object> core = coreOf(record);
            groupName = jsonSafe(core.get("group_name")) == null ? null : String.valueOf(core.get("group_name"));
            groupType = jsonSafe(core.get("group_type")) == null ? null : String.valueOf(core.get("group_type"));
            groupStatus = jsonSafe(core.get("group_status")) == null ? null : String.valueOf(core.get("group_status"));
            groupStateOwned = jsonSafe(core.get("state_owned_flag")) == null ? null : String.valueOf(core.get("state_owned_flag"));
            groupUcrCode = jsonSafe(core.get("ucr_code")) == null ? null : String.valueOf(core.get("ucr_code"));
            break;
        }
        if (groupName == null) {
            List<Map<String, Object>> dw = jdbcTemplate.queryForList(
                    "SELECT group_name, group_type, group_status, state_owned_flag, ucr_code FROM dw_customer_group_snapshot"
                            + " WHERE group_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_customer_group_snapshot WHERE group_no = ?)",
                    groupNo, groupNo);
            if (!dw.isEmpty() && dw.get(0).get("group_name") != null) {
                groupName = String.valueOf(dw.get(0).get("group_name"));
                groupType = dw.get(0).get("group_type") == null ? null : String.valueOf(dw.get(0).get("group_type"));
                groupStatus = dw.get(0).get("group_status") == null ? null : String.valueOf(dw.get(0).get("group_status"));
                groupStateOwned = dw.get(0).get("state_owned_flag") == null ? null : String.valueOf(dw.get(0).get("state_owned_flag"));
                groupUcrCode = dw.get(0).get("ucr_code") == null ? null : String.valueOf(dw.get(0).get("ucr_code"));
            }
        }
        cn.hutool.json.JSONObject gi = null;
        Object gij = application == null ? null : application.get("groupInfoJson");
        if (gij != null && StrUtil.isNotBlank(gij.toString())) {
            try {
                gi = JSONUtil.parseObj(gij.toString());
            } catch (Exception ignore) {
                // 补录 JSON 非法时忽略,集团名仍可来自快照/数仓
            }
        }
        if (groupName == null && gi != null) {
            groupName = gi.getStr("groupName");
        }
        if (groupName == null) {
            groupName = "集团-" + groupNo;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("customerNo", groupNo);
        row.put("customerName", groupName);
        row.put("certType", "USCC");
        // 集团统一社会信用代码:补录(新增集团/人工)值优先,存量集团回退快照/数仓 ucr_code(2026-09-03 修复「申请页有/审批无」)
        String certNo = groupUcrCode;
        if (gi != null) {
            if (StrUtil.isNotBlank(gi.getStr("ucrCode"))) {
                certNo = gi.getStr("ucrCode");
            }
            row.put("fiveLevelClass", gi.getStr("fiveLevelClass"));
            row.put("creditLevel", gi.getStr("creditLevel"));
            row.put("industry", gi.getStr("industry"));
            row.put("registeredCapital", jsonSafe(gi.get("registeredCapital")));
            row.put("openOrgName", gi.getStr("openOrg"));
            row.put("openDate", gi.getStr("openDate"));
            row.put("basicAccount", gi.getStr("basicAccount"));
            row.put("currency", gi.getStr("currency"));
            row.put("applyAmount", jsonSafe(gi.get("applyAmount")));
        }
        row.put("groupNo", groupNo);
        row.put("groupName", groupName);
        row.put("groupType", groupType == null ? "INDUSTRY_GROUP" : groupType);
        row.put("groupStatus", groupStatus);
        row.put("stateOwnedFlag", groupStateOwned);
        row.put("custType", "CORP");
        if (certNo != null) {
            row.put("certNo", certNo);
        }
        return List.of(row);
    }

    /** 集团成员信息补充:成员名称 + 完整对公要素。
     * 名称/对公要素优先级一致:快照数仓成员主数据(CORPORATE core_json) → 申请补录 group_info_json.supplementMembers(手工成员) → 实时数仓降级。
     * 输出键对齐前端对公模板(certNo/fiveLevelClass/creditLevel/industry/registeredCapital/openOrgName/openDate/basicAccount)。 */
    private void enrichMemberNames(List<Map<String, Object>> members, List<Map<String, Object>> snapshotRecords,
                                   Map<String, Object> application) {
        Map<String, String> nameByNo = new HashMap<>();
        Map<String, Map<String, Object>> corpCoreByNo = new HashMap<>();
        for (Map<String, Object> record : snapshotRecords) {
            Object sid = record.get("subjectId");
            if (sid == null) {
                continue;
            }
            String no = sid.toString();
            Map<String, Object> core = coreOf(record);
            if ("CORPORATE".equals(record.get("subjectType"))) {
                if (jsonSafe(core.get("cust_name")) != null) {
                    nameByNo.putIfAbsent(no, String.valueOf(core.get("cust_name")));
                }
                corpCoreByNo.putIfAbsent(no, core);
            } else if ("MEMBER".equals(record.get("subjectType")) && jsonSafe(core.get("member_name")) != null) {
                nameByNo.putIfAbsent(no, String.valueOf(core.get("member_name")));
            }
        }
        // 手工成员对公要素仅存在于申请上下文 group_info_json.supplementMembers(未落业务表)
        Map<String, cn.hutool.json.JSONObject> manualByNo = new HashMap<>();
        Object gij = application == null ? null : application.get("groupInfoJson");
        if (gij != null && StrUtil.isNotBlank(gij.toString())) {
            try {
                cn.hutool.json.JSONObject gi = JSONUtil.parseObj(gij.toString());
                Object supp = gi.get("supplementMembers");
                if (supp instanceof cn.hutool.json.JSONArray arr) {
                    for (Object item : arr) {
                        if (!(item instanceof cn.hutool.json.JSONObject m)) {
                            continue;
                        }
                        String no = m.getStr("memberCustomerNo");
                        if (no == null) {
                            continue;
                        }
                        manualByNo.put(no, m);
                        if (m.getStr("memberName") != null) {
                            nameByNo.putIfAbsent(no, m.getStr("memberName"));
                        }
                    }
                }
            } catch (Exception ignore) {
                // 补录 JSON 非法时忽略,成员名称仍可来自快照/实时数仓
            }
        }
        for (Map<String, Object> member : members) {
            String no = String.valueOf(member.get("memberCustomerNo"));
            String name = nameByNo.get(no);
            if (name == null) {
                name = realtimeMemberName(no);
            }
            member.put("memberName", name);
            Map<String, Object> corp = corpCoreByNo.get(no);
            if (corp != null) {
                applyCorpMember(member, corp);
            } else {
                cn.hutool.json.JSONObject manual = manualByNo.get(no);
                if (manual != null) {
                    applyManualMember(member, manual);
                } else {
                    applyRealtimeMember(member, no);
                }
            }
        }
    }

    /** 数仓成员对公要素(快照 CORPORATE core_json)映射到前端对公模板键;epoch 毫秒日期统一归一 */
    private void applyCorpMember(Map<String, Object> member, Map<String, Object> core) {
        member.put("certNo", jsonSafe(core.get("cert_no")));
        member.put("certType", jsonSafe(core.get("cert_tp")));
        member.put("fiveLevelClass", jsonSafe(core.get("ffthlv_class")));
        member.put("creditLevel", jsonSafe(core.get("crdt_grd")));
        member.put("industry", jsonSafe(core.get("blgd_idsty")));
        member.put("registeredCapital", jsonSafe(core.get("reg_cap")));
        member.put("openOrgName", jsonSafe(core.get("openact_org_nm")));
        member.put("openDate", snapshotDate(jsonSafe(core.get("openact_dt"))));
        member.put("basicAccount", jsonSafe(core.get("basic_account_no")));
        member.put("customerClass", jsonSafe(core.get("cust_class")));
        member.put("empeNum", jsonSafe(core.get("entp_empe_num")));
        member.put("estbDate", snapshotDate(jsonSafe(core.get("estp_estb_dt"))));
        member.put("totalAssets", jsonSafe(core.get("rest_asts")));
        member.put("restAddr", jsonSafe(core.get("rest_addr")));
    }

    /** 快照日期归一:数仓 DATE 列被快照冻结为 epoch 毫秒(数字或13位数字串),统一转 yyyy-MM-dd;字符串原样返回 */
    private static Object snapshotDate(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n && n.longValue() > 0) {
            return DateUtil.format(DateUtil.date(n.longValue()), "yyyy-MM-dd");
        }
        String s = v.toString().trim();
        if (s.matches("\\d{13}")) {
            return DateUtil.format(DateUtil.date(Long.parseLong(s)), "yyyy-MM-dd");
        }
        return v;
    }

    /** 手工成员对公要素(申请补录 group_info_json.supplementMembers)映射到前端对公模板键;证件类型统一 USCC */
    private void applyManualMember(Map<String, Object> member, cn.hutool.json.JSONObject m) {
        member.put("certNo", m.getStr("ucrCode"));
        member.put("certType", "USCC");
        member.put("fiveLevelClass", m.getStr("fiveLevelClass"));
        member.put("creditLevel", m.getStr("creditLevel"));
        member.put("industry", m.getStr("industry"));
        member.put("registeredCapital", jsonSafe(m.get("registeredCapital")));
        member.put("openOrgName", m.getStr("openOrg"));
        member.put("openDate", m.getStr("openDate"));
        member.put("basicAccount", m.getStr("basicAccount"));
    }

    /** 成员对公要素实时降级:数仓对公客户主数据(快照/补录均缺失时兜底) */
    private void applyRealtimeMember(Map<String, Object> member, String memberNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT cert_no certNo, cert_tp certType, entp_charic entpCharic, entp_scale entpScale,"
                        + " blgd_idsty industry, crdt_grd creditLevel, ffthlv_class fiveLevelClass,"
                        + " reg_cap registeredCapital, openact_org_nm openOrgName, openact_dt openDate,"
                        + " basic_account_no basicAccount, cust_class customerClass, entp_empe_num empeNum,"
                        + " estp_estb_dt estbDate, rest_asts totalAssets, rest_addr restAddr"
                        + " FROM caps_corp_cust_basic_info WHERE cust_no = ? LIMIT 1", memberNo);
        if (!rows.isEmpty()) {
            member.putAll(rows.get(0));
        }
    }

    /** 成员名称实时降级:数仓对公主数据 → 对私主数据 → 手工成员表(补录成员名) */
    private String realtimeMemberName(String memberNo) {
        List<Map<String, Object>> corp = jdbcTemplate.queryForList(
                "SELECT cust_name FROM caps_corp_cust_basic_info WHERE cust_no = ? LIMIT 1", memberNo);
        if (!corp.isEmpty() && corp.get(0).get("cust_name") != null) {
            return String.valueOf(corp.get(0).get("cust_name"));
        }
        List<Map<String, Object>> indv = jdbcTemplate.queryForList(
                "SELECT cust_nm FROM caps_indv_cust_basic_info WHERE cust_no = ? LIMIT 1", memberNo);
        if (!indv.isEmpty() && indv.get(0).get("cust_nm") != null) {
            return String.valueOf(indv.get(0).get("cust_nm"));
        }
        List<Map<String, Object>> manual = jdbcTemplate.queryForList(
                "SELECT member_name FROM ccr_group_member WHERE member_customer_no = ? AND del_flag = '0' LIMIT 1", memberNo);
        if (!manual.isEmpty() && manual.get(0).get("member_name") != null) {
            return String.valueOf(manual.get(0).get("member_name"));
        }
        return null;
    }

    /** 存款分项账户视图(§12.7):分项-账户关系 + 快照 DEPOSIT_ACCOUNT 记录,无快照降级数仓按明文账号;账号明文展示 */
    private List<Map<String, Object>> depositAccountView(Long pricingItemId, List<Map<String, Object>> snapshotRecords) {
        List<Map<String, Object>> rels = jdbcTemplate.queryForList(
                "SELECT deposit_account_no accountNo, planned_account_flag planned"
                        + " FROM ccr_pricing_item_deposit_rel WHERE pricing_item_id = ? AND del_flag = '0'", pricingItemId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> rel : rels) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("plannedAccountFlag", "Y".equals(rel.get("planned")) ? "Y" : "N");
            Object accountNo = rel.get("accountNo");
            row.put("accountNo", accountNo);
            Map<String, Object> acct = null;
            for (Map<String, Object> record : snapshotRecords) {
                if (!"DEPOSIT_ACCOUNT".equals(record.get("subjectType"))) {
                    continue;
                }
                Map<String, Object> core = coreOf(record);
                if (accountNo != null && accountNo.equals(core.get("deposit_account_no"))) {
                    acct = core;
                    break;
                }
                if (acct == null) {
                    acct = core; // 无账号(拟开户)时兜底取包内第一条账户记录
                }
            }
            if (acct == null && accountNo != null) {
                List<Map<String, Object>> dw = jdbcTemplate.queryForList(
                        "SELECT * FROM dw_deposit_account_snapshot WHERE deposit_account_no = ?"
                                + " ORDER BY data_dt DESC, etl_md5 DESC LIMIT 1", accountNo);
                if (!dw.isEmpty()) {
                    acct = dw.get(0);
                }
            }
            if (acct != null) {
                row.put("productCode", acct.get("product_code"));
                row.put("accountBalance", acct.get("account_balance"));
                row.put("currency", acct.get("currency"));
                row.put("executionRate", acct.get("execution_rate"));
                row.put("termValue", acct.get("term_value"));
                row.put("termUnit", acct.get("term_unit"));
                row.put("openDate", acct.get("open_date"));
                row.put("maturityDate", acct.get("maturity_date"));
                row.put("accountStatus", acct.get("account_status"));
            }
            rows.add(row);
        }
        return rows;
    }

    /** 查询当前用户待办(按登录人角色过滤,不再传 nodeCode/operatorId) */
    @GetMapping("/tasks")
    public R<List<CcrPricingItem>> tasks() {
        return R.ok(approvalService.listTodo());
    }

    /** 普通节点通过(整单交付改造 2026-08-29:按申请整单审批,可携带整单调价利率);
     * body 传 applicationId(整单化新口径),兼容旧前端 pricingItemId 解析;versionNo 兼容传参,Idempotency-Key 头可选 */
    @PostMapping("/tasks/approve")
    public R<ApprovalResult> approve(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                           @RequestBody Map<String, Object> body) {
        ApprovalResult result = approvalService.approve(
                resolveApplicationId(body),
                body.get("nodeCode").toString(),
                body.get("adjustRate") == null ? null : new BigDecimal(body.get("adjustRate").toString()),
                body.get("comment") == null ? null : body.get("comment").toString(),
                body.get("versionNo") == null ? null : Integer.valueOf(body.get("versionNo").toString()),
                idempotencyKey, parseRateAdjustments(body.get("rateAdjustments")));
        return R.ok(result);
    }

    /** 解析同申请其余分项调价利率(分项id→调整后利率,仅收录有变化的分项;空/非对象返回 null) */
    private Map<Long, BigDecimal> parseRateAdjustments(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return null;
        }
        Map<Long, BigDecimal> result = new HashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            result.put(Long.valueOf(e.getKey().toString()), new BigDecimal(e.getValue().toString()));
        }
        return result.isEmpty() ? null : result;
    }

    /** 普通节点否决(整单交付改造 2026-08-29:一次否决即整单否决);
     * body 传 applicationId(整单化新口径),兼容旧前端 pricingItemId 解析;versionNo 兼容传参,Idempotency-Key 头可选 */
    @PostMapping("/tasks/reject")
    public R<ApprovalResult> reject(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                    @RequestBody Map<String, Object> body) {
        ApprovalResult result = approvalService.reject(
                resolveApplicationId(body),
                body.get("nodeCode").toString(),
                body.get("comment") == null ? null : body.get("comment").toString(),
                body.get("versionNo") == null ? null : Integer.valueOf(body.get("versionNo").toString()),
                idempotencyKey);
        return R.ok(result);
    }

    /** 解析审批动作目标申请 id:整单化优先 applicationId,兼容旧前端 pricingItemId */
    private Long resolveApplicationId(Map<String, Object> body) {
        Object appId = body.get("applicationId");
        if (appId == null) {
            appId = body.get("pricingItemId");
        }
        if (appId == null) {
            throw new ServiceException(400, "缺少审批目标 applicationId(或兼容参数 pricingItemId)");
        }
        return Long.valueOf(appId.toString());
    }

    /**
     * 审批中客户号回填(2026-08-20 #017):新增客户提交时数仓未收录 → 占位号,审批中数仓已收录后回填真实号。
     * body 支持 customerNo(真实客户号)或 certNo(按证件号反查数仓),二选一。
     */
    @PostMapping("/{pricingItemId}/backfill-customer-no")
    public R<Void> backfillCustomerNo(@PathVariable Long pricingItemId, @RequestBody Map<String, Object> body) {
        String customerNo = body.get("customerNo") == null ? null : body.get("customerNo").toString().trim();
        String certNo = body.get("certNo") == null ? null : body.get("certNo").toString().trim();
        if (StrUtil.isBlank(customerNo) && StrUtil.isBlank(certNo)) {
            throw new ServiceException(400, "回填需提供真实客户号或证件号(customerNo/certNo)");
        }
        approvalService.backfillCustomerNo(pricingItemId, customerNo, certNo);
        return R.ok();
    }

    /**
     * §2026-09-02 节点进入自动回填(决策二):单户占位申请进入审批详情时自动触发——
     * 按 customer_info_json 证件号反查数仓,命中即整单占位→真实并级联(主单/分项/快照/关联人绑定/
     * 关联人自身客户号);未命中不写库、不阻塞流程(仍保留页面人工「回填客户号」兜底)。
     */
    @PostMapping("/{applicationId}/auto-backfill-customer-no")
    public R<AutoBackfillResult> autoBackfillCustomerNo(@PathVariable Long applicationId) {
        applicationAccessService.requireView(applicationId);
        return R.ok(approvalService.autoBackfillCustomerNo(applicationId));
    }

    /** 已办:当前登录人办理过的任务列表(§11.4) */
    @GetMapping("/done")
    public R<List<Map<String, Object>>> done() {
        return R.ok(approvalService.listDone());
    }

    /** 历史审批分页(§13.2/§14.4,按登录人角色/数据权限;§2026-08-26 支持申请号/状态/客户名称筛选) */
    @GetMapping("/history")
    public R<Map<String, Object>> history(@RequestParam(defaultValue = "1") int pageNum,
                                          @RequestParam(defaultValue = "10") int pageSize,
                                          @RequestParam(required = false) String applicationNo,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String keyword) {
        return R.ok(approvalService.pageHistory(pageNum, pageSize, applicationNo, status, keyword));
    }

    /** 申请审批档案(§14.4) */
    @GetMapping("/history/{applicationId}")
    public R<Map<String, Object>> historyDetail(@PathVariable Long applicationId) {
        applicationAccessService.requireView(applicationId);
        return R.ok(approvalService.historyDetail(applicationId));
    }

    /** 授信协议历史审批申请(§2026-09-01 存量授信展示:审批详情授信信息卡片按协议查历史申请审批状态) */
    @GetMapping("/agreement-history")
    public R<List<Map<String, Object>>> agreementHistory(@RequestParam String agreementNo) {
        return R.ok(approvalService.agreementHistory(agreementNo));
    }
}
