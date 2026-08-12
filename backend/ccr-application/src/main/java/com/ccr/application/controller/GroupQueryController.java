package com.ccr.application.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.hutool.core.util.StrUtil;
import com.ccr.application.service.DataWarehouseService;
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
 */
@RestController
@SaCheckRole("customer_manager")
public class GroupQueryController {

    @Resource
    private DataWarehouseService dataWarehouseService;

    /** 集团联想(申请页集团号/集团名下联想选择,§13.1) */
    @GetMapping("/ccr/groups/suggest")
    public R<List<Map<String, Object>>> suggest(@RequestParam String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return R.ok(new ArrayList<>());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : dataWarehouseService.suggestGroups(keyword.trim())) {
            result.add(camel(row));
        }
        return R.ok(result);
    }

    /** 集团 + 集团授信概况 */
    @GetMapping("/ccr/groups/{groupNo}")
    public R<Map<String, Object>> group(@PathVariable String groupNo) {
        Map<String, Object> group = dataWarehouseService.findGroup(groupNo);
        if (group == null) {
            throw new ServiceException(404, "集团不存在:" + groupNo);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("group", camel(group));
        Map<String, Object> credit = dataWarehouseService.findGroupCredit(groupNo);
        result.put("groupCredit", credit == null ? null : camel(credit));
        if (credit != null) {
            List<Map<String, Object>> limits =
                    dataWarehouseService.memberLimitsByGroup(String.valueOf(credit.get("group_credit_no")));
            result.put("allocatedTotal", limits.stream()
                    .map(l -> l.get("allocated_amount"))
                    .filter(java.util.Objects::nonNull)
                    .map(v -> new java.math.BigDecimal(v.toString()))
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
        }
        return R.ok(result);
    }

    /** 集团有效成员及额度(在团:relation_end 空或未到期) */
    @GetMapping("/ccr/groups/{groupNo}/members")
    public R<List<Map<String, Object>>> groupMembers(@PathVariable String groupNo) {
        Map<String, Object> group = dataWarehouseService.findGroup(groupNo);
        if (group == null) {
            throw new ServiceException(404, "集团不存在:" + groupNo);
        }
        Map<String, Object> credit = dataWarehouseService.findGroupCredit(groupNo);
        String groupCreditNo = credit == null ? null : String.valueOf(credit.get("group_credit_no"));
        List<Map<String, Object>> members = new ArrayList<>();
        for (Map<String, Object> member : dataWarehouseService.groupMembers(groupNo)) {
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

    /** 成员额度/用信分项/合同/合同下借据/担保视图 */
    @GetMapping("/ccr/members/{customerNo}/credit-view")
    public R<Map<String, Object>> memberCreditView(@PathVariable String customerNo) {
        Map<String, Object> corp = dataWarehouseService.findCorpCustomer(customerNo);
        Map<String, Object> indv = corp == null ? dataWarehouseService.findIndvCustomer(customerNo) : null;
        if (corp == null && indv == null) {
            throw new ServiceException(404, "成员客户不存在:" + customerNo);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("customer", camel(corp != null ? corp : indv));

        // 成员额度 → 用信分项
        List<Map<String, Object>> limits = new ArrayList<>();
        for (Map<String, Object> limit : dataWarehouseService.memberLimitsByMember(customerNo)) {
            Map<String, Object> row = camel(limit);
            List<Map<String, Object>> tranches = new ArrayList<>();
            for (Map<String, Object> tranche :
                    dataWarehouseService.tranchesByLimit(String.valueOf(limit.get("member_limit_no")))) {
                tranches.add(camel(tranche));
            }
            row.put("tranches", tranches);
            limits.add(row);
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

    /** 数仓行 snake_case 键转 camelCase */
    static Map<String, Object> camel(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((k, v) -> result.put(StrUtil.toCamelCase(k), v));
        return result;
    }
}
