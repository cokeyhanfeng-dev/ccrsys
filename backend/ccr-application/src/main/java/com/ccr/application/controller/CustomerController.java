package com.ccr.application.controller;

import com.ccr.application.service.DataWarehouseService;
import com.ccr.common.core.domain.R;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户查询接口(数仓数据:申请按姓名模糊查询带出客户/融资/贡献度)
 * 数据源:caps_* 与 dw_* 外部表(当前为模拟数据,数仓推送后同结构)
 */
@RestController
@RequestMapping("/ccr/customers")
public class CustomerController {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private DataWarehouseService dataWarehouseService;

    /** 按客户姓名模糊查询(对公+对私),返回候选客户 */
    @GetMapping
    public R<List<Map<String, Object>>> search(@RequestParam String name) {
        if (name == null || name.isBlank()) {
            return R.ok(List.of());
        }
        String like = "%" + name + "%";
        String sql = """
                SELECT cust_no AS customerNo, cust_name AS customerName, 'CORP' AS custType, cust_class AS customerClass
                FROM caps_corp_cust_basic_info
                WHERE cust_name LIKE ? AND data_dt = (SELECT MAX(data_dt) FROM caps_corp_cust_basic_info)
                UNION ALL
                SELECT cust_no AS customerNo, cust_nm AS customerName, 'INDV' AS custType, cust_class AS customerClass
                FROM caps_indv_cust_basic_info
                WHERE cust_nm LIKE ? AND data_dt = (SELECT MAX(data_dt) FROM caps_indv_cust_basic_info)
                """;
        return R.ok(jdbcTemplate.queryForList(sql, like, like));
    }

    /** 客户详情:基本信息 + 本行融资 + 当前贡献度 + 他行融资(申请带出) */
    @GetMapping("/{customerNo}")
    public R<Map<String, Object>> detail(@PathVariable String customerNo) {
        Map<String, Object> result = new LinkedHashMap<>();
        // 1. 基本信息(对公优先,无则对私)
        List<Map<String, Object>> corp = jdbcTemplate.queryForList("""
                SELECT cust_no customerNo, cust_name customerName, cert_tp certType, cert_no certNo, ffthlv_class fiveLevelClass,
                       entp_charic entpCharic, entp_scale entpScale, blgd_idsty industry, crdt_grd creditLevel, rest_asts registeredCapital,
                       openact_org_nm openOrgName, openact_dt openDate, cust_class customerClass
                FROM caps_corp_cust_basic_info WHERE cust_no = ? LIMIT 1""", customerNo);
        if (!corp.isEmpty()) {
            result.put("basic", corp.get(0));
            result.put("custType", "CORP");
        } else {
            List<Map<String, Object>> indv = jdbcTemplate.queryForList("""
                    SELECT cust_no customerNo, cust_nm customerName, cert_tp certType, cert_no certNo, ocupn occupation,
                           whlyr_incm annualIncome, mrrg_sittn maritalStatus, tel_no phone, cust_class customerClass
                    FROM caps_indv_cust_basic_info WHERE cust_no = ? LIMIT 1""", customerNo);
            if (indv.isEmpty()) {
                throw new ServiceException(404, "客户不存在");
            }
            result.put("basic", indv.get(0));
            result.put("custType", "INDV");
        }
        // 2. 本行融资
        result.put("financing", jdbcTemplate.queryForList("""
                SELECT contract_no contractNo, loan_balance loanBalance, contract_rate contractRate, guarantee_type guaranteeType
                FROM dw_own_financing_snapshot WHERE cust_no = ?""", customerNo));
        // 3. 当前贡献度
        result.put("contribution", jdbcTemplate.queryForList("""
                SELECT metric_code metricCode, metric_name metricName, metric_value metricValue, value_type valueType, metric_scope metricScope
                FROM dw_contribution_metric WHERE cust_no = ?""", customerNo));
        // 4. 他行融资概要 + 明细
        result.put("creditSummary", jdbcTemplate.queryForList("""
                SELECT lender_count lenderCount, credit_amount_total creditAmountTotal, used_amount_total usedAmountTotal,
                       npl_balance nplBalance, overdue_account_count overdueAccountCount
                FROM dw_credit_financing_summary WHERE cust_no = ? LIMIT 1""", customerNo));
        result.put("creditDetail", jdbcTemplate.queryForList("""
                SELECT lender_name lenderName, credit_amount creditAmount, used_amount usedAmount, balance_amount balanceAmount, annual_rate annualRate
                FROM dw_credit_financing_detail WHERE cust_no = ?""", customerNo));
        return R.ok(result);
    }

    /** 客户业务视图(§13.1:账户/授信/合同/合同下借据/担保/贡献度概况,最新批次) */
    @GetMapping("/{customerNo}/business-view")
    public R<Map<String, Object>> businessView(@PathVariable String customerNo) {
        Map<String, Object> corp = dataWarehouseService.findCorpCustomer(customerNo);
        Map<String, Object> indv = corp == null ? dataWarehouseService.findIndvCustomer(customerNo) : null;
        if (corp == null && indv == null) {
            throw new ServiceException(404, "客户不存在:" + customerNo);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("customer", GroupQueryController.camel(corp != null ? corp : indv));
        result.put("custType", corp != null ? "CORP" : "INDV");
        // 本行融资(授信概况)
        result.put("financing", camelRows(dataWarehouseService.ownFinancing(customerNo)));
        // 存款账户
        result.put("depositAccounts", camelRows(dataWarehouseService.depositAccounts(customerNo)));
        // 合同 + 合同下借据
        List<Map<String, Object>> contracts = new java.util.ArrayList<>();
        for (Map<String, Object> contract : dataWarehouseService.contractsByBorrower(customerNo)) {
            Map<String, Object> row = GroupQueryController.camel(contract);
            row.put("notes", camelRows(dataWarehouseService.notesByContract(String.valueOf(contract.get("contract_no")))));
            contracts.add(row);
        }
        result.put("contracts", contracts);
        // 担保区块(抵押物+担保人)
        Map<String, Object> guarantees = new LinkedHashMap<>();
        guarantees.put("mortgages", camelRows(dataWarehouseService.mortgages(customerNo)));
        guarantees.put("guarantors", camelRows(dataWarehouseService.guarantors(customerNo)));
        result.put("guarantees", guarantees);
        // 贡献度概况
        result.put("contribution", camelRows(dataWarehouseService.contribution(customerNo)));
        return R.ok(result);
    }

    private List<Map<String, Object>> camelRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            result.add(GroupQueryController.camel(row));
        }
        return result;
    }
}
