package com.ccr.application.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import cn.hutool.core.util.StrUtil;
import com.ccr.application.domain.CcrGroup;
import com.ccr.application.domain.CcrGroupMember;
import com.ccr.application.service.DataWarehouseService;
import com.ccr.application.service.ManualGroupService;
import com.ccr.common.core.domain.R;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 集团/成员查询接口(§13.1;db/02 数仓表最新批次取数,只读)
 * <p>集团/成员查询合并数仓快照与手工集团主数据(ccr_group/ccr_group_member):
 * 数仓优先,手工集团(数仓未统计)回退 ccr_group;手工集团无数仓授信,批复总额度补录用于授信概况。</p>
 */
@RestController
@SaCheckRole(value = {"customer_manager", "admin"}, mode = SaMode.OR)
public class GroupQueryController {

    @Resource
    private DataWarehouseService dataWarehouseService;

    @Resource
    private ManualGroupService manualGroupService;

    /** 集团联想(申请页集团号/集团名下联想选择,§13.1;数仓优先,手工集团回退) */
    @GetMapping("/ccr/groups/suggest")
    public R<List<Map<String, Object>>> suggest(@RequestParam String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return R.ok(new ArrayList<>());
        }
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> row : dataWarehouseService.suggestGroups(keyword.trim())) {
            Map<String, Object> c = camel(row);
            merged.put(String.valueOf(c.get("groupNo")), c);
        }
        for (Map<String, Object> row : manualGroupService.suggest(keyword.trim())) {
            merged.putIfAbsent(String.valueOf(row.get("groupNo")), row);
        }
        return R.ok(new ArrayList<>(merged.values()));
    }

    /** 集团 + 集团授信概况(数仓优先,手工集团回退 ccr_group 补录批复总额度) */
    @GetMapping("/ccr/groups/{groupNo}")
    public R<Map<String, Object>> group(@PathVariable String groupNo) {
        Map<String, Object> group = mergedGroup(groupNo);
        if (group == null) {
            throw new ServiceException(404, "集团不存在:" + groupNo);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("group", camel(group));
        Map<String, Object> credit = mergedCredit(groupNo);
        result.put("groupCredit", credit == null ? null : camel(credit));
        if (credit != null) {
            List<Map<String, Object>> limits = dataWarehouseService.memberLimitsByGroup(
                    credit.get("group_credit_no") == null ? null : String.valueOf(credit.get("group_credit_no")));
            result.put("allocatedTotal", limits.stream()
                    .map(l -> l.get("allocated_amount"))
                    .filter(java.util.Objects::nonNull)
                    .map(v -> new java.math.BigDecimal(v.toString()))
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
        }
        return R.ok(result);
    }

    /** 集团有效成员及额度(在团:relation_end 空或未到期;数仓优先,手工成员回退带补录名称) */
    @GetMapping("/ccr/groups/{groupNo}/members")
    public R<List<Map<String, Object>>> groupMembers(@PathVariable String groupNo) {
        if (mergedGroup(groupNo) == null) {
            throw new ServiceException(404, "集团不存在:" + groupNo);
        }
        Map<String, Object> credit = mergedCredit(groupNo);
        String groupCreditNo = credit == null ? null : String.valueOf(credit.get("group_credit_no"));
        List<Map<String, Object>> members = new ArrayList<>();
        List<Map<String, Object>> dwMembers = dataWarehouseService.groupMembers(groupNo);
        if (dwMembers == null || dwMembers.isEmpty()) {
            // 手工成员:名称直接取补录值,无数仓额度
            for (CcrGroupMember m : manualGroupService.listMembers(groupNo)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("member_customer_no", m.getMemberCustomerNo());
                row.put("member_role", m.getMemberRole());
                row.put("control_relation", m.getControlRelation());
                row.put("is_core_member", "CORE".equals(m.getMemberRole()) ? "Y" : "N");
                row.put("relation_start", m.getRelationStart());
                row.put("relation_end", m.getRelationEnd());
                Map<String, Object> cr = camel(row);
                cr.put("memberName", m.getMemberName());
                cr.put("creditLimit", null);
                members.add(cr);
            }
            return R.ok(members);
        }
        for (Map<String, Object> member : dwMembers) {
            Map<String, Object> row = camel(member);
            String memberNo = String.valueOf(member.get("member_customer_no"));
            Map<String, Object> corp = dataWarehouseService.findCorpCustomer(memberNo);
            row.put("memberName", corp == null ? null : corp.get("cust_name"));
            Map<String, Object> limit = groupCreditNo == null ? null
                    : dataWarehouseService.findMemberLimit(groupCreditNo, memberNo);
            row.put("creditLimit", limit == null ? null : camel(limit));
            members.add(row);
        }
        return R.ok(members);
    }

    /** 成员额度/合同/合同下借据/担保视图 */
    @GetMapping("/ccr/members/{customerNo}/credit-view")
    public R<Map<String, Object>> memberCreditView(@PathVariable String customerNo) {
        Map<String, Object> corp = dataWarehouseService.findCorpCustomer(customerNo);
        Map<String, Object> indv = corp == null ? dataWarehouseService.findIndvCustomer(customerNo) : null;
        if (corp == null && indv == null) {
            throw new ServiceException(404, "成员客户不存在:" + customerNo);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("customer", camel(corp != null ? corp : indv));

        // 成员额度(合同直接挂成员额度下,无用信分项层)
        List<Map<String, Object>> limits = new ArrayList<>();
        for (Map<String, Object> limit : dataWarehouseService.memberLimitsByMember(customerNo)) {
            limits.add(camel(limit));
        }
        result.put("creditLimits", limits);

        // 合同(含合同下借据)
        result.put("contracts", contractsWithNotes(customerNo));

        // 担保区块(抵押物+担保人)
        Map<String, Object> guarantees = new LinkedHashMap<>();
        guarantees.put("mortgages", camelRows(dataWarehouseService.mortgages(customerNo)));
        guarantees.put("guarantors", camelRows(dataWarehouseService.guarantors(customerNo)));
        result.put("guarantees", guarantees);
        return R.ok(result);
    }

    // ---------- 私有 ----------

    /** 借款人名下合同(每份合同嵌套其下借据) */
    private List<Map<String, Object>> contractsWithNotes(String customerNo) {
        List<Map<String, Object>> contracts = new ArrayList<>();
        for (Map<String, Object> contract : dataWarehouseService.contractsByBorrower(customerNo)) {
            Map<String, Object> row = camel(contract);
            row.put("notes", camelRows(dataWarehouseService.notesByContract(String.valueOf(contract.get("contract_no")))));
            contracts.add(row);
        }
        return contracts;
    }

    private List<Map<String, Object>> camelRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            result.add(camel(row));
        }
        return result;
    }

    /** 集团行(snake 键;数仓优先,手工集团回退 ccr_group) */
    private Map<String, Object> mergedGroup(String groupNo) {
        Map<String, Object> dw = dataWarehouseService.findGroup(groupNo);
        if (dw != null) {
            return dw;
        }
        CcrGroup g = manualGroupService.findGroup(groupNo);
        if (g == null) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("group_no", g.getGroupNo());
        row.put("group_name", g.getGroupName());
        row.put("group_type", g.getGroupType());
        row.put("manager_org_id", g.getManagerOrgId());
        row.put("group_status", g.getGroupStatus());
        row.put("state_owned_flag", g.getStateOwnedFlag());
        return row;
    }

    /** 集团授信行(snake 键;数仓优先,手工集团无授信快照用补录批复总额度构造) */
    private Map<String, Object> mergedCredit(String groupNo) {
        Map<String, Object> dw = dataWarehouseService.findGroupCredit(groupNo);
        if (dw != null) {
            return dw;
        }
        CcrGroup g = manualGroupService.findGroup(groupNo);
        if (g == null || g.getApprovedTotalAmount() == null) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("approved_total_amount", g.getApprovedTotalAmount());
        row.put("allocated_amount", java.math.BigDecimal.ZERO);
        row.put("used_amount", java.math.BigDecimal.ZERO);
        row.put("available_amount", g.getApprovedTotalAmount());
        row.put("currency", g.getCurrency());
        return row;
    }

    /** 数仓行 snake_case 键转 camelCase */
    static Map<String, Object> camel(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((k, v) -> result.put(StrUtil.toCamelCase(k), v));
        return result;
    }
}
