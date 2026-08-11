package com.ccr.approval.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.ccr.application.domain.CcrPricingItem;
import com.ccr.approval.service.ApprovalService;
import com.ccr.approval.support.RouteChains;
import com.ccr.common.core.domain.R;
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
    private JdbcTemplate jdbcTemplate;

    /**
     * 审批详情:定价分项+申请+客户+融资+贡献度+担保+流程路由(routeChain)+资料校验结果(质量 PASS/WARN/BLOCK)
     * +拟达成贡献度(ccr_application_commitment)+流程轨迹;集团场景返回成员级明细。
     * 客户/融资/贡献度优先读提交时冻结快照(source=SNAPSHOT,含 snapshotInfo),无快照降级数仓(source=REALTIME);
     * 另含历史履约(tracking)与机构达成(orgPerformance)
     */
    @GetMapping("/{pricingItemId}/detail")
    public R<Map<String, Object>> detail(@PathVariable Long pricingItemId) {
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT * FROM ccr_pricing_item WHERE id = ? AND del_flag = '0'", pricingItemId);
        if (items.isEmpty()) {
            throw new ServiceException(404, "定价分项不存在");
        }
        Map<String, Object> item = items.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pricingItem", item);

        Long appId = (item.get("application_id") instanceof Number)
                ? ((Number) item.get("application_id")).longValue() : null;
        String custNo = item.get("pricing_customer_no") == null ? "" : item.get("pricing_customer_no").toString();
        String businessType = null;

        // 申请概要
        if (appId != null) {
            List<Map<String, Object>> apps = jdbcTemplate.queryForList(
                    "SELECT id, application_no applicationNo, business_type businessType, customer_no customerNo, group_no groupNo, application_remark applicationRemark, snapshot_bundle_id snapshotBundleId, customer_info_json customerInfoJson, submit_time submitTime, applicant_user_id applicantUserId FROM ccr_application WHERE id = ?", appId);
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
        // 流程路由链:首节点至终审岗位(route_code)
        Object routeCode = item.get("route_code");
        result.put("routeChain", RouteChains.of(businessType, routeCode == null ? null : routeCode.toString()));
        // 拟达成贡献度(申请承诺指标,按申请关联 §十三 13.2-6;成员级含成员客户号)
        if (appId != null) {
            result.put("commitments", jdbcTemplate.queryForList(
                    "SELECT metric_code metricCode, target_type targetType, baseline_value baselineValue, target_value targetValue, unit, metric_scope metricScope, member_customer_no memberCustomerNo, commitment_desc commitmentDesc FROM ccr_application_commitment WHERE application_id = ? ORDER BY id", appId));
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
            submit.put("nodeCode", item.get("start_node_code"));
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

        // 当前执行状态:当前节点 + 状态 + 到达当前节点时间(当前节点最早动作时间;首节点回退提交时间)
        result.put("currentNodeCode", item.get("current_node_code"));
        result.put("currentStatus", item.get("status"));
        String nodeReachTime = submitTimeObj == null ? null : submitTimeObj.toString();
        Object curNode = item.get("current_node_code");
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
        if (!snapshotRecords.isEmpty()) {
            result.put("source", "SNAPSHOT");
            result.put("customer", snapshotCustomer(snapshotRecords, custNo));
            result.put("financing", snapshotFinancing(snapshotRecords, custNo));
            result.put("contribution", snapshotContribution(snapshotRecords, custNo));
            result.put("snapshotInfo", snapshotInfo(bundleId, snapshotRecords));
        } else {
            // 降级:数仓实时查询
            result.put("source", "REALTIME");
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
            result.put("financing", jdbcTemplate.queryForList(
                    "SELECT contract_no contractNo, agreement_no agreementNo, borrower_customer_no borrowerCustomerNo,"
                    + " contract_amount contractAmount, contract_balance loanBalance, guarantee_type guaranteeType,"
                    + " execution_rate contractRate FROM dw_loan_contract_snapshot WHERE borrower_customer_no = ?", custNo));
            result.put("contribution", jdbcTemplate.queryForList(
                    "SELECT metric_code metricCode, metric_name metricName, metric_value metricValue, value_type valueType FROM dw_contribution_metric WHERE cust_no = ?", custNo));
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

        // 历史履约:该客户承诺跟踪最新评估结果(每指标取最新 data_dt)
        result.put("tracking", jdbcTemplate.queryForList("""
                SELECT cp.plan_no planNo, cm.metric_code metricCode, cm.target_value targetValue,
                       te.data_dt dataDt, te.actual_value actualValue, te.achievement_ratio achievementRatio,
                       te.result_status resultStatus, te.risk_level riskLevel
                FROM ccr_tracking_evaluation te
                JOIN (SELECT metric_id, MAX(data_dt) max_dt FROM ccr_tracking_evaluation GROUP BY metric_id) t
                     ON t.metric_id = te.metric_id AND t.max_dt = te.data_dt
                JOIN ccr_commitment_metric cm ON cm.id = te.metric_id
                JOIN ccr_commitment_plan cp ON cp.id = te.plan_id
                WHERE cp.del_flag = '0' AND (cp.customer_no = ? OR cp.member_customer_no = ?)
                ORDER BY cm.metric_code
                """, custNo, custNo));

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
                            + " pi.status, pi.version_no versionNo, pi.original_rate originalRate,"
                            + " pi.term_value termValue, pi.term_unit termUnit, pi.product_code productCode,"
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
                }
                // 已通过集合:任意节点「权限内 APPROVE」(超权限转送为 ESCALATE 不在其中)→ 上级已通过的分项后续节点只展示、不重复审批
                List<Map<String, Object>> allApproves = jdbcTemplate.queryForList(
                        "SELECT DISTINCT pricing_item_id pricingItemId FROM ccr_approval_action"
                                + " WHERE action_type = 'APPROVE' AND pricing_item_id IN (" + inSb + ") AND del_flag = '0'");
                for (Map<String, Object> a : allApproves) {
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
                for (Map<String, Object> s : siblings) {
                    s.put("agreed", agreed.contains(s.get("id")));
                    s.put("passed", passed.contains(s.get("id")));
                    s.put("guarantees", guaranteeByItem.getOrDefault(s.get("id"), Collections.emptyList()));
                }
            }
            result.put("siblingItems", siblings);
        } else {
            result.put("siblingItems", List.of());
        }

        // 授信协议(§12.7:授信协议编号/类型/起止/额度/已用,数仓最新批次)
        result.put("creditAgreements", jdbcTemplate.queryForList(
                "SELECT agreement_no agreementNo, agreement_type agreementType, credit_amount creditAmount, used_amount usedAmount, available_amount availableAmount, currency, start_date startDate, end_date endDate, agreement_status agreementStatus FROM dw_credit_agreement_snapshot WHERE customer_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_credit_agreement_snapshot WHERE customer_no = ?) ORDER BY agreement_no", custNo, custNo));

        // 他行融资(申请人工补录/Excel 导入与数仓征信,最新批次)
        result.put("otherLoanSummary", jdbcTemplate.queryForList(
                "SELECT lender_count lenderCount, npl_balance nplBalance, credit_amount_total creditAmountTotal, used_amount_total usedAmountTotal, loan_account_count loanAccountCount, overdue_account_count overdueAccountCount, overdue_balance overdueBalance, special_mention_balance specialMentionBalance, external_guarantee_balance externalGuaranteeBalance FROM dw_credit_financing_summary WHERE cust_no = ? ORDER BY data_dt DESC LIMIT 1", custNo));
        result.put("otherLoans", jdbcTemplate.queryForList(
                "SELECT lender_name lenderName, credit_amount creditAmount, used_amount usedAmount, balance_amount balanceAmount, annual_rate annualRate, 'DW' inputMode FROM dw_credit_financing_detail WHERE customer_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_credit_financing_detail WHERE customer_no = ?)", custNo, custNo));

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
                    "SELECT person_name personName, cert_no certNo, relation_type relationType, related_customer_no relatedCustomerNo FROM ccr_application_related_person WHERE application_id = ? AND del_flag = '0' ORDER BY id", appId);
            enrichRelated(relatedPersons);
            result.put("relatedPersons", relatedPersons);
        }
        // 关联人(数仓客户关系快照,最新批次;按关联客户号补全基本信息/授信信息)
        List<Map<String, Object>> relations = jdbcTemplate.queryForList(
                "SELECT related_customer_no relatedCustomerNo, relation_type relationType, relation_strength relationStrength FROM dw_customer_relation_snapshot WHERE customer_no = ? AND relation_status = 'VALID' AND data_dt = (SELECT MAX(data_dt) FROM dw_customer_relation_snapshot WHERE customer_no = ?)", custNo, custNo);
        enrichRelated(relations);
        result.put("relations", relations);
        return R.ok(result);
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
                        + " WHERE cust_no IN (" + in + ") AND data_dt = (SELECT MAX(data_dt) FROM caps_indv_cust_basic_info)", args)) {
            basics.put(String.valueOf(iv.get("custNo")), iv);
        }
        for (Map<String, Object> c : jdbcTemplate.queryForList(
                "SELECT cust_no custNo, entp_charic entpCharic, entp_scale entpScale, blgd_idsty industry, crdt_grd creditLevel, ffthlv_class fiveLevelClass"
                        + " FROM caps_corp_cust_basic_info WHERE cust_no IN (" + in + ") AND data_dt = (SELECT MAX(data_dt) FROM caps_corp_cust_basic_info)", args)) {
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
        if (manual == null || !manual.containsKey(srcKey) || manual.get(srcKey) == null) {
            return;
        }
        String v = manual.get(srcKey).toString();
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
            row.put("customerNo", corp.get("cust_no"));
            row.put("customerName", corp.get("cust_name"));
            row.put("certNo", corp.get("cert_no"));
            row.put("entpCharic", corp.get("entp_charic"));
            row.put("entpScale", corp.get("entp_scale"));
            row.put("industry", corp.get("blgd_idsty"));
            row.put("creditLevel", corp.get("crdt_grd"));
            row.put("fiveLevelClass", corp.get("ffthlv_class"));
            row.put("empeNum", corp.get("entp_empe_num"));
            row.put("totalAssets", corp.get("rest_asts"));
            row.put("estbDate", corp.get("estp_estb_dt"));
            row.put("restAddr", corp.get("rest_addr"));
            row.put("openOrgName", corp.get("openact_org_nm"));
            row.put("openDate", corp.get("openact_dt"));
            row.put("customerClass", corp.get("cust_class"));
            row.put("custType", "CORP");
            row.put("dataSource", corp.get("data_source"));
            return List.of(row);
        }
        if (indv != null) {
            // 对私客户基本信息(证件/职业/年收入/婚姻/居住/联系电话等)
            row.put("customerNo", indv.get("cust_no"));
            row.put("customerName", indv.get("cust_nm"));
            row.put("certType", indv.get("cert_tp"));
            row.put("certNo", indv.get("cert_no"));
            row.put("gender", indv.get("gnd"));
            row.put("occupation", indv.get("ocupn"));
            row.put("annualIncome", indv.get("whlyr_incm"));
            row.put("maritalStatus", indv.get("mrrg_sittn"));
            row.put("address", indv.get("rsd_addr"));
            row.put("phone", indv.get("tel_no"));
            row.put("openOrgName", indv.get("opnact_org_nm"));
            row.put("openDate", indv.get("opnact_dt"));
            row.put("fiveLevelClass", indv.get("ffthlv_class"));
            row.put("customerClass", indv.get("cust_class"));
            row.put("dataSource", indv.get("data_source"));
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
            Object coreCust = core.get("borrower_customer_no");
            if (coreCust == null) {
                coreCust = core.get("cust_no");
            }
            if (!custNo.equals(coreCust)) {
                continue;
            }
            Object loanBalance = core.get("loan_balance");
            if (loanBalance == null) {
                loanBalance = core.get("contract_balance");
            }
            Object contractRate = core.get("contract_rate");
            if (contractRate == null) {
                contractRate = core.get("execution_rate");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("contractNo", core.get("contract_no"));
            row.put("loanBalance", loanBalance);
            row.put("contractRate", contractRate);
            row.put("guaranteeType", core.get("guarantee_type"));
            rows.add(row);
        }
        return rows;
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
                row.put("metricCode", metric.get("metric_code"));
                row.put("metricName", metric.get("metric_name"));
                row.put("metricValue", metric.get("metric_value"));
                row.put("valueType", metric.get("value_type"));
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

    /** 机构达成(§12.16):申请机构 → ccr_sys_dept.dept_code → 数仓最新批次 */
    private List<Map<String, Object>> orgPerformance(Long appId) {
        if (appId == null) {
            return List.of();
        }
        List<Map<String, Object>> orgCodes = jdbcTemplate.queryForList(
                "SELECT d.dept_code orgCode FROM ccr_application a JOIN ccr_sys_dept d ON d.id = a.applicant_org_id"
                        + " WHERE a.id = ? AND d.del_flag = '0'", appId);
        if (orgCodes.isEmpty() || orgCodes.get(0).get("orgCode") == null) {
            return List.of();
        }
        return jdbcTemplate.queryForList(
                "SELECT org_code orgCode, stat_month statMonth, achieved_amount achievedAmount,"
                        + " expected_amount expectedAmount, completion_rate completionRate, data_dt dataDt"
                        + " FROM dw_org_performance_snapshot WHERE org_code = ?"
                        + " ORDER BY data_dt DESC LIMIT 1", orgCodes.get(0).get("orgCode"));
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

    /** 存款分项账户视图(§12.7):分项-账户关系 + 快照 DEPOSIT_ACCOUNT 记录,无快照降级数仓按哈希;账号脱敏 */
    private List<Map<String, Object>> depositAccountView(Long pricingItemId, List<Map<String, Object>> snapshotRecords) {
        List<Map<String, Object>> rels = jdbcTemplate.queryForList(
                "SELECT deposit_account_no_cipher cipher, deposit_account_hash hash, planned_account_flag planned"
                        + " FROM ccr_pricing_item_deposit_rel WHERE pricing_item_id = ? AND del_flag = '0'", pricingItemId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> rel : rels) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("plannedAccountFlag", "Y".equals(rel.get("planned")) ? "Y" : "N");
            Object cipher = rel.get("cipher");
            row.put("accountNoMasked", maskAccount(cipher == null ? null : cipher.toString()));
            Object hash = rel.get("hash");
            Map<String, Object> acct = null;
            for (Map<String, Object> record : snapshotRecords) {
                if (!"DEPOSIT_ACCOUNT".equals(record.get("subjectType"))) {
                    continue;
                }
                Map<String, Object> core = coreOf(record);
                if (hash != null && hash.equals(core.get("deposit_account_hash"))) {
                    acct = core;
                    break;
                }
                if (acct == null) {
                    acct = core; // 无哈希(拟开户)时兜底取包内第一条账户记录
                }
            }
            if (acct == null && hash != null) {
                List<Map<String, Object>> dw = jdbcTemplate.queryForList(
                        "SELECT * FROM dw_deposit_account_snapshot WHERE deposit_account_hash = ?"
                                + " ORDER BY data_dt DESC, etl_md5 DESC LIMIT 1", hash);
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

    /** 账号脱敏:CIPHER_9550880000000101 → 9550****0101 */
    private String maskAccount(String cipher) {
        if (cipher == null || cipher.isBlank()) {
            return null;
        }
        String no = cipher.startsWith("CIPHER_") ? cipher.substring(7) : cipher;
        return no.length() > 8 ? no.substring(0, 4) + "****" + no.substring(no.length() - 4) : "****";
    }

    /** 查询当前用户待办(按登录人角色过滤,不再传 nodeCode/operatorId) */
    @GetMapping("/tasks")
    public R<List<CcrPricingItem>> tasks() {
        return R.ok(approvalService.listTodo());
    }

    /** 普通节点通过(可携带分项审批利率);versionNo 必传,Idempotency-Key 头可选 */
    @PostMapping("/tasks/approve")
    public R<Void> approve(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                           @RequestBody Map<String, Object> body) {
        approvalService.approve(
                Long.valueOf(body.get("pricingItemId").toString()),
                body.get("nodeCode").toString(),
                body.get("adjustRate") == null ? null : new BigDecimal(body.get("adjustRate").toString()),
                body.get("comment") == null ? null : body.get("comment").toString(),
                body.get("versionNo") == null ? null : Integer.valueOf(body.get("versionNo").toString()),
                idempotencyKey);
        return R.ok();
    }

    /** 普通节点否决;versionNo 必传,Idempotency-Key 头可选 */
    @PostMapping("/tasks/reject")
    public R<Void> reject(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                          @RequestBody Map<String, Object> body) {
        approvalService.reject(
                Long.valueOf(body.get("pricingItemId").toString()),
                body.get("nodeCode").toString(),
                body.get("comment") == null ? null : body.get("comment").toString(),
                body.get("versionNo") == null ? null : Integer.valueOf(body.get("versionNo").toString()),
                idempotencyKey);
        return R.ok();
    }

    /** 已办:当前登录人办理过的任务列表(§11.4) */
    @GetMapping("/done")
    public R<List<Map<String, Object>>> done() {
        return R.ok(approvalService.listDone());
    }

    /** 历史审批分页(§13.2/§14.4,按登录人角色/数据权限) */
    @GetMapping("/history")
    public R<Map<String, Object>> history(@RequestParam(defaultValue = "1") int pageNum,
                                          @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(approvalService.pageHistory(pageNum, pageSize));
    }

    /** 申请审批档案(§14.4) */
    @GetMapping("/history/{applicationId}")
    public R<Map<String, Object>> historyDetail(@PathVariable Long applicationId) {
        return R.ok(approvalService.historyDetail(applicationId));
    }
}
