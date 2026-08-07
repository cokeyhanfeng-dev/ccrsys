package com.ccr.approval.controller;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * +拟达成贡献度(ccr_application_commitment)+流程轨迹;集团场景返回成员级明细
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
                "SELECT action_type actionType, node_code nodeCode, operator_id operatorId, action_comment actionComment, before_rate beforeRate, after_rate afterRate, operation_time operationTime FROM ccr_approval_action WHERE pricing_item_id = ? AND del_flag = '0' ORDER BY operation_time", pricingItemId));

        // 客户信息(caps 对公优先,无则对私)
        List<Map<String, Object>> corp = jdbcTemplate.queryForList(
                "SELECT cust_no customerNo, cust_name customerName, entp_charic entpCharic, blgd_idsty industry, crdt_grd creditLevel, ffthlv_class fiveLevelClass, openact_org_nm openOrgName, cust_class customerClass FROM caps_corp_cust_basic_info WHERE cust_no = ? LIMIT 1", custNo);
        result.put("customer", corp.isEmpty()
                ? jdbcTemplate.queryForList("SELECT cust_no customerNo, cust_nm customerName, cust_class customerClass FROM caps_indv_cust_basic_info WHERE cust_no = ? LIMIT 1", custNo)
                : corp);
        // 本行融资
        result.put("financing", jdbcTemplate.queryForList(
                "SELECT contract_no contractNo, loan_balance loanBalance, contract_rate contractRate, guarantee_type guaranteeType FROM dw_own_financing_snapshot WHERE cust_no = ?", custNo));
        // 当前贡献度
        result.put("contribution", jdbcTemplate.queryForList(
                "SELECT metric_code metricCode, metric_name metricName, metric_value metricValue, value_type valueType FROM dw_contribution_metric WHERE cust_no = ?", custNo));
        // 担保分项
        result.put("guarantees", jdbcTemplate.queryForList(
                "SELECT gp.main_guarantee_type guaranteeType, gp.package_version packageVersion, gm.measure_no measureNo, gm.measure_type measureType, gm.guarantee_amount guaranteeAmount FROM ccr_guarantee_package gp LEFT JOIN ccr_guarantee_measure gm ON gm.package_id = gp.id WHERE gp.pricing_item_id = ? AND gp.del_flag = '0'", pricingItemId));
        return R.ok(result);
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
