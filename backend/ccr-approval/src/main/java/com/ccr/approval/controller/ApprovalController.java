package com.ccr.approval.controller;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
                    "SELECT id, application_no applicationNo, business_type businessType, customer_no customerNo, group_no groupNo, application_remark applicationRemark, snapshot_bundle_id snapshotBundleId FROM ccr_application WHERE id = ?", appId);
            result.put("application", apps);
            if (!apps.isEmpty()) {
                businessType = apps.get(0).get("businessType") == null ? null : apps.get(0).get("businessType").toString();
                // 集团场景:本次涉及成员(成员客户号真实存在,成员级明细)
                if (apps.get(0).get("groupNo") != null) {
                    result.put("groupMembers", jdbcTemplate.queryForList(
                            "SELECT member_customer_no memberCustomerNo, member_role memberRole, request_amount requestAmount FROM ccr_application_member WHERE application_id = ? AND del_flag = '0'", appId));
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
        // 拟达成贡献度(申请承诺指标,成员级含成员客户号)
        result.put("commitments", jdbcTemplate.queryForList(
                "SELECT metric_code metricCode, target_type targetType, baseline_value baselineValue, target_value targetValue, unit, metric_scope metricScope, member_customer_no memberCustomerNo FROM ccr_application_commitment WHERE pricing_item_id = ?", pricingItemId));
        // 流程轨迹(审批动作)
        result.put("flowTrace", jdbcTemplate.queryForList(
                "SELECT action_type actionType, node_code nodeCode, operator_id operatorId, action_comment actionComment, before_rate beforeRate, after_rate afterRate, from_status fromStatus, to_status toStatus, operation_time operationTime FROM ccr_approval_action WHERE pricing_item_id = ? AND del_flag = '0' ORDER BY operation_time", pricingItemId));

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
                    "SELECT cust_no customerNo, cust_name customerName, entp_charic entpCharic, blgd_idsty industry, crdt_grd creditLevel, ffthlv_class fiveLevelClass, openact_org_nm openOrgName, cust_class customerClass FROM caps_corp_cust_basic_info WHERE cust_no = ? LIMIT 1", custNo);
            result.put("customer", corp.isEmpty()
                    ? jdbcTemplate.queryForList("SELECT cust_no customerNo, cust_nm customerName, cust_class customerClass FROM caps_indv_cust_basic_info WHERE cust_no = ? LIMIT 1", custNo)
                    : corp);
            result.put("financing", jdbcTemplate.queryForList(
                    "SELECT contract_no contractNo, loan_balance loanBalance, contract_rate contractRate, guarantee_type guaranteeType FROM dw_own_financing_snapshot WHERE cust_no = ?", custNo));
            result.put("contribution", jdbcTemplate.queryForList(
                    "SELECT metric_code metricCode, metric_name metricName, metric_value metricValue, value_type valueType FROM dw_contribution_metric WHERE cust_no = ?", custNo));
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

        // 存款分项:账户区块(§12.7 存款场景;快照优先,含拟开户标识与脱敏账号)
        if ("DEPOSIT".equals(businessType)) {
            result.put("depositAccounts", depositAccountView(pricingItemId, snapshotRecords));
        }

        // 担保分项(含措施扩展字段,审批端按担保类型完整展示申请录入内容)
        result.put("guarantees", jdbcTemplate.queryForList(
                "SELECT gp.main_guarantee_type guaranteeType, gp.package_version packageVersion, gm.measure_no measureNo, gm.measure_type measureType, gm.guarantee_amount guaranteeAmount, gm.ext_json extJson FROM ccr_guarantee_package gp LEFT JOIN ccr_guarantee_measure gm ON gm.package_id = gp.id WHERE gp.pricing_item_id = ? AND gp.del_flag = '0'", pricingItemId));

        // 他行融资(申请人工补录/Excel 导入与数仓征信,最新批次)
        result.put("otherLoanSummary", jdbcTemplate.queryForList(
                "SELECT lender_count lenderCount, credit_amount_total creditAmountTotal, used_amount_total usedAmountTotal, npl_balance nplBalance, overdue_account_count overdueAccountCount FROM dw_credit_financing_summary WHERE cust_no = ? ORDER BY data_dt DESC LIMIT 1", custNo));
        result.put("otherLoans", jdbcTemplate.queryForList(
                "SELECT lender_name lenderName, credit_amount creditAmount, used_amount usedAmount, balance_amount balanceAmount, annual_rate annualRate, 'DW' inputMode FROM dw_credit_financing_detail WHERE customer_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_credit_financing_detail WHERE customer_no = ?)", custNo, custNo));

        // 申请人工补录/Excel 导入的他行融资(随单持久化;与数仓征信分行展示)
        if (appId != null) {
            result.put("appOtherLoans", jdbcTemplate.queryForList(
                    "SELECT lender_name lenderName, credit_amount creditAmount, used_amount usedAmount, balance_amount balanceAmount, annual_rate annualRate, input_mode inputMode FROM ccr_application_other_loan WHERE application_id = ? AND del_flag = '0' ORDER BY id", appId));
        }

        // 关联人(数仓客户关系快照,最新批次)
        result.put("relations", jdbcTemplate.queryForList(
                "SELECT related_customer_no relatedCustomerNo, relation_type relationType, relation_strength relationStrength FROM dw_customer_relation_snapshot WHERE customer_no = ? AND relation_status = 'VALID' AND data_dt = (SELECT MAX(data_dt) FROM dw_customer_relation_snapshot WHERE customer_no = ?)", custNo, custNo));
        return R.ok(result);
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
            row.put("customerNo", corp.get("cust_no"));
            row.put("customerName", corp.get("cust_name"));
            row.put("entpCharic", corp.get("entp_charic"));
            row.put("industry", corp.get("blgd_idsty"));
            row.put("creditLevel", corp.get("crdt_grd"));
            row.put("fiveLevelClass", corp.get("ffthlv_class"));
            row.put("openOrgName", corp.get("openact_org_nm"));
            row.put("customerClass", corp.get("cust_class"));
            return List.of(row);
        }
        if (indv != null) {
            row.put("customerNo", indv.get("cust_no"));
            row.put("customerName", indv.get("cust_nm"));
            row.put("customerClass", indv.get("cust_class"));
            return List.of(row);
        }
        return List.of();
    }

    /** 快照内本行融资(FINANCING 记录,按 cust_no 过滤) */
    private List<Map<String, Object>> snapshotFinancing(List<Map<String, Object>> records, String custNo) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> record : records) {
            if (!"FINANCING".equals(record.get("subjectType"))) {
                continue;
            }
            Map<String, Object> core = coreOf(record);
            if (!custNo.equals(core.get("cust_no"))) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("contractNo", core.get("contract_no"));
            row.put("loanBalance", core.get("loan_balance"));
            row.put("contractRate", core.get("contract_rate"));
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
