package com.ccr.application.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.ccr.application.service.DataWarehouseService;
import com.ccr.application.service.ManualGroupService;
import com.ccr.application.support.AppLoginUser;
import com.ccr.common.core.domain.R;
import com.ccr.common.core.util.ContributionMerger;
import com.ccr.common.core.util.RelatedCustomerResolver;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 客户查询接口(数仓数据:申请按姓名模糊查询带出客户/融资/贡献度)
 * 数据源:caps_* 与 dw_* 外部表(当前为模拟数据,数仓推送后同结构)
 */
@RestController
@RequestMapping("/ccr/customers")
@SaCheckRole(value = {"customer_manager", "admin"}, mode = SaMode.OR)
public class CustomerController {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private DataWarehouseService dataWarehouseService;

    @Resource
    private ManualGroupService manualGroupService;

    @Resource
    private AppLoginUser appLoginUser;

    /**
     * 按客户姓名或客户号模糊查询(对公+对私),返回候选客户。
     * 管户过滤(2026-08-24 需求①):mgr_no 空=无管户,所有客户经理可见;非空=仅管户客户经理本人可见。
     */
    @GetMapping
    public R<List<Map<String, Object>>> search(@RequestParam String name) {
        if (name == null || name.isBlank()) {
            return R.ok(List.of());
        }
        String like = "%" + name + "%";
        String mgrNo = appLoginUser.requireCurrentUser().getUsername();
        String sql = """
                SELECT cust_no AS customerNo, cust_name AS customerName, 'CORP' AS custType, cust_class AS customerClass
                FROM caps_corp_cust_basic_info
                WHERE (cust_name LIKE ? OR cust_no LIKE ?) AND (mgr_no IS NULL OR mgr_no = ?) AND data_dt = (SELECT MAX(d2.data_dt) FROM caps_corp_cust_basic_info d2 WHERE d2.cust_no = caps_corp_cust_basic_info.cust_no)
                UNION ALL
                SELECT cust_no AS customerNo, cust_nm AS customerName, 'INDV' AS custType, cust_class AS customerClass
                FROM caps_indv_cust_basic_info
                WHERE (cust_nm LIKE ? OR cust_no LIKE ?) AND (mgr_no IS NULL OR mgr_no = ?) AND data_dt = (SELECT MAX(d2.data_dt) FROM caps_indv_cust_basic_info d2 WHERE d2.cust_no = caps_indv_cust_basic_info.cust_no)
                """;
        return R.ok(jdbcTemplate.queryForList(sql, like, like, mgrNo, like, like, mgrNo));
    }

    /**
     * 按证件类型+证件号反查客户(关联人录入自动带出姓名/客户号,§2026-08-26)。
     * 对公 USCC 查 caps_corp_cust_basic_info,对私 ID_CARD 查 caps_indv_cust_basic_info;
     * 最新批次 + etl_md5 DESC 取一条,命中返回 {customerNo, customerName, custType},未命中返回 null。
     * 不做管户过滤(关联人可为其他支行客户,仅补全信息不涉及数据范围)。
     */
    @GetMapping("/by-cert")
    public R<Map<String, Object>> searchByCert(@RequestParam String certType, @RequestParam String certNo) {
        if (certNo == null || certNo.isBlank()) {
            return R.ok(null);
        }
        if ("USCC".equals(certType)) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT cust_no customerNo, cust_name customerName, 'CORP' custType
                    FROM caps_corp_cust_basic_info
                    WHERE cert_no = ? AND data_dt = (SELECT MAX(d2.data_dt) FROM caps_corp_cust_basic_info d2 WHERE d2.cert_no = caps_corp_cust_basic_info.cert_no)
                    ORDER BY etl_md5 DESC LIMIT 1""", certNo);
            return R.ok(rows.isEmpty() ? null : rows.get(0));
        }
        if ("ID_CARD".equals(certType)) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT cust_no customerNo, cust_nm customerName, 'INDV' custType
                    FROM caps_indv_cust_basic_info
                    WHERE cert_no = ? AND data_dt = (SELECT MAX(d2.data_dt) FROM caps_indv_cust_basic_info d2 WHERE d2.cert_no = caps_indv_cust_basic_info.cert_no)
                    ORDER BY etl_md5 DESC LIMIT 1""", certNo);
            return R.ok(rows.isEmpty() ? null : rows.get(0));
        }
        return R.ok(null);
    }

    /** 开户机构下拉(§用户要求):启用机构列表,客户经理填单选填开户机构(数据源 ccr_sys_dept,与数仓开户机构名对齐)
     *  2026-08-25 补回:增量022曾加入,后续重构丢失导致 /{customerNo} 抢占 open-orgs → 申请页初始化报"客户不存在" */
    @GetMapping("/open-orgs")
    public R<List<Map<String, Object>>> openOrgs() {
        return R.ok(jdbcTemplate.queryForList("""
                SELECT id, org_code orgCode, dept_name deptName
                FROM ccr_sys_dept
                WHERE del_flag = '0' AND status = 'ENABLE'
                ORDER BY FIELD(org_type, 'HEAD', 'DEPT', 'BRANCH', 'NETWORK'), sort_no"""));
    }

    /** 客户详情:基本信息 + 本行融资 + 当前贡献度 + 他行融资(申请带出) */
    @GetMapping("/{customerNo}")
    public R<Map<String, Object>> detail(@PathVariable String customerNo) {
        Map<String, Object> result = new LinkedHashMap<>();
        // 1. 基本信息(对公优先,无则对私)
        List<Map<String, Object>> corp = jdbcTemplate.queryForList("""
                SELECT cust_no customerNo, cust_name customerName, cert_tp certType, cert_no certNo, ffthlv_class fiveLevelClass,
                       entp_charic entpCharic, entp_scale entpScale, blgd_idsty industry, crdt_grd creditLevel, rest_asts registeredCapital,
                       openact_org_nm openOrgName, openact_dt openDate, basic_account_no basicAccount, cust_class customerClass, mgr_no mgrNo
                FROM caps_corp_cust_basic_info
                WHERE cust_no = ? AND data_dt = (SELECT MAX(d2.data_dt) FROM caps_corp_cust_basic_info d2 WHERE d2.cust_no = caps_corp_cust_basic_info.cust_no) LIMIT 1""", customerNo);
        if (!corp.isEmpty()) {
            assertManagerPermitted(corp.get(0));
            Map<String, Object> basic = corp.get(0);
            // 集团成员单户判定(2026-09-01):数仓优先,手工集团回退;命中带出集团归属,前端阻断单户申请
            Map<String, Object> groupOf = dataWarehouseService.groupOfCustomer(customerNo);
            if (groupOf == null) {
                groupOf = manualGroupService.groupOfCustomer(customerNo);
            }
            if (groupOf != null) {
                basic.put("groupNo", groupOf.get("groupNo"));
                basic.put("groupName", groupOf.get("groupName"));
            }
            result.put("basic", basic);
            result.put("custType", "CORP");
        } else {
            List<Map<String, Object>> indv = jdbcTemplate.queryForList("""
                    SELECT cust_no customerNo, cust_nm customerName, cert_tp certType, cert_no certNo, ocupn occupation,
                           whlyr_incm annualIncome, mrrg_sittn maritalStatus, tel_no phone, cust_class customerClass, mgr_no mgrNo
                    FROM caps_indv_cust_basic_info
                    WHERE cust_no = ? AND data_dt = (SELECT MAX(d2.data_dt) FROM caps_indv_cust_basic_info d2 WHERE d2.cust_no = caps_indv_cust_basic_info.cust_no) LIMIT 1""", customerNo);
            if (indv.isEmpty()) {
                throw new ServiceException(404, "客户不存在");
            }
            assertManagerPermitted(indv.get(0));
            result.put("basic", indv.get(0));
            result.put("custType", "INDV");
        }
        // 2. 本行融资/存量贷款(2026-08-11 去冗余:原 dw_own_financing 并入贷款合同)
        result.put("financing", jdbcTemplate.queryForList("""
                SELECT contract_no contractNo, agreement_no agreementNo, borrower_customer_no borrowerCustomerNo,
                       contract_amount contractAmount, contract_balance loanBalance, guarantee_type guaranteeType,
                       execution_rate contractRate, currency
                FROM dw_loan_contract_snapshot WHERE borrower_customer_no = ?""", customerNo));
        // 3. 当前贡献度(关联人贡献度归并:数仓有效关联人同码值加总,§关联人贡献度归并)
        result.put("contribution", mergeWithRelated(jdbcTemplate.queryForList("""
                SELECT metric_code metricCode, metric_name metricName, metric_value metricValue, value_type valueType, metric_scope metricScope
                FROM dw_contribution_metric WHERE cust_no = ?""", customerNo), customerNo));
        // 4. 他行融资概要 + 明细(报告日期=数仓征信报告日期 dw_credit_report_snapshot,§2026-08-26)
        result.put("creditSummary", jdbcTemplate.queryForList("""
                SELECT f.lender_count lenderCount, f.npl_balance nplBalance, f.credit_amount_total creditAmountTotal,
                       f.used_amount_total usedAmountTotal, f.loan_account_count loanAccountCount,
                       f.overdue_account_count overdueAccountCount, f.overdue_balance overdueBalance,
                       f.special_mention_balance specialMentionBalance, f.external_guarantee_balance externalGuaranteeBalance,
                       (SELECT r.report_date FROM dw_credit_report_snapshot r WHERE r.cust_no = f.cust_no
                        ORDER BY r.data_dt DESC, r.report_date DESC LIMIT 1) reportDate
                FROM dw_credit_financing_summary f WHERE f.cust_no = ? LIMIT 1""", customerNo));
        result.put("creditDetail", jdbcTemplate.queryForList("""
                SELECT lender_name lenderName, credit_amount creditAmount, used_amount usedAmount, balance_amount balanceAmount, annual_rate annualRate
                FROM dw_credit_financing_detail WHERE customer_no = ?""", customerNo));
        return R.ok(result);
    }

    /** 当前贡献度归并关联人:仅前台录入的申请关联人(ccr_application_related_person),同 metric_code 值加总进主客户(§关联人贡献度归并) */
    private List<Map<String, Object>> mergeWithRelated(List<Map<String, Object>> contribution, String customerNo) {
        if (contribution == null || contribution.isEmpty()) {
            return contribution;
        }
        // 前台录入关联人:按申请客户号反查历史申请的关联人(数仓推的关系不参与归并);
        // related_customer_no 为空时按证件号兜底反查数仓主数据补全
        List<Map<String, Object>> relations = jdbcTemplate.queryForList(
                "SELECT rp.related_customer_no relatedCustomerNo, rp.cert_type certType, rp.cert_no certNo"
                        + " FROM ccr_application_related_person rp"
                        + " JOIN ccr_application a ON a.id = rp.application_id AND a.del_flag = '0'"
                        + " WHERE a.customer_no = ? AND rp.del_flag = '0'", customerNo);
        RelatedCustomerResolver.resolveBatch(jdbcTemplate, relations);
        Set<String> relatedNos = new LinkedHashSet<>();
        for (Map<String, Object> rel : relations) {
            Object no = rel.get("relatedCustomerNo");
            if (no != null && !no.toString().isBlank()) {
                relatedNos.add(no.toString());
            }
        }
        ContributionMerger.mergeRelatedContributions(jdbcTemplate, contribution, relatedNos);
        return contribution;
    }

    /** 存款账号反查(输入明文账号,查数仓最新批次;命中返回账户信息,未命中返回 null) */
    @GetMapping("/{customerNo}/deposit-account-lookup")
    public R<Map<String, Object>> depositAccountLookup(@PathVariable String customerNo, @RequestParam String accountNo) {
        if (cn.hutool.core.util.StrUtil.isBlank(accountNo)) {
            throw new ServiceException(400, "存款账号必填");
        }
        Map<String, Object> row = dataWarehouseService.findDepositAccountByNo(accountNo.trim());
        // 未命中或账号不属于该客户,均按未命中处理(不泄露他户账户信息)
        if (row == null || !customerNo.equals(String.valueOf(row.get("customer_no")))) {
            return R.ok(null);
        }
        return R.ok(GroupQueryController.camel(row));
    }

    /** 存款账户列表(数仓最新批次;存量调价下拉选择,轻量接口避免 business-view 全量,§2026-08-25) */
    @GetMapping("/{customerNo}/deposit-accounts")
    public R<List<Map<String, Object>>> depositAccounts(@PathVariable String customerNo) {
        Map<String, Object> corp = dataWarehouseService.findCorpCustomer(customerNo);
        Map<String, Object> indv = corp == null ? dataWarehouseService.findIndvCustomer(customerNo) : null;
        if (corp == null && indv == null) {
            throw new ServiceException(404, "客户不存在:" + customerNo);
        }
        assertManagerPermitted(corp != null ? corp : indv);
        return R.ok(camelRows(dataWarehouseService.depositAccounts(customerNo)));
    }

    /** 客户业务视图(§13.1:账户/授信/合同/合同下借据/担保/贡献度概况,最新批次) */
    @GetMapping("/{customerNo}/business-view")
    public R<Map<String, Object>> businessView(@PathVariable String customerNo) {
        Map<String, Object> corp = dataWarehouseService.findCorpCustomer(customerNo);
        Map<String, Object> indv = corp == null ? dataWarehouseService.findIndvCustomer(customerNo) : null;
        if (corp == null && indv == null) {
            throw new ServiceException(404, "客户不存在:" + customerNo);
        }
        // 管户权限校验:数仓行带 mgr_no(工号),非本人管户客户经理不可查看
        assertManagerPermitted(corp != null ? corp : indv);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("customer", GroupQueryController.camel(corp != null ? corp : indv));
        result.put("custType", corp != null ? "CORP" : "INDV");
        // 本行融资(授信概况)
        result.put("financing", camelRows(dataWarehouseService.ownFinancing(customerNo)));
        // 授信协议(授信信息卡:协议编号/类型/起止/额度/已用)
        result.put("creditAgreements", camelRows(dataWarehouseService.creditAgreements(customerNo)));
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
        // 授信担保拆分明细(需求②:存量利率申请按数仓拆分项勾选,每项内嵌担保措施 T21+T22)
        List<Map<String, Object>> splits = dataWarehouseService.creditSplits(customerNo);
        List<String> splitNos = splits.stream().map(s -> String.valueOf(s.get("split_no"))).toList();
        List<Map<String, Object>> splitMeasures = dataWarehouseService.splitMeasures(splitNos);
        for (Map<String, Object> sp : splits) {
            String sn = String.valueOf(sp.get("split_no"));
            sp.put("measures", camelRows(splitMeasures.stream()
                    .filter(m -> sn.equals(String.valueOf(m.get("split_no")))).toList()));
        }
        result.put("creditSplits", camelRows(splits));
        // 贡献度概况
        result.put("contribution", camelRows(dataWarehouseService.contribution(customerNo)));
        return R.ok(result);
    }

    /**
     * 管户权限(需求①,2026-08-24):数仓客户 mgr_no 为空=无管户,任何客户经理可拉出;
     * mgr_no 非空=有管户客户经理,仅该工号本人可查看。非本人 → 403。
     */
    private void assertManagerPermitted(Map<String, Object> custRow) {
        // 兼容两种键名:detail 内联 SQL 用别名(mgr_no mgrNo),businessView 数仓行用下划线键(mgr_no)
        Object mgrNo = custRow.get("mgr_no");
        if (mgrNo == null) {
            mgrNo = custRow.get("mgrNo");
        }
        if (mgrNo == null || String.valueOf(mgrNo).isBlank()) {
            return;
        }
        String currentUsername = appLoginUser.requireCurrentUser().getUsername();
        if (!String.valueOf(mgrNo).equals(currentUsername)) {
            throw new ServiceException(403, "无权限查看该客户(该客户由客户经理[" + mgrNo + "]管户)");
        }
    }

    private List<Map<String, Object>> camelRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            result.add(GroupQueryController.camel(row));
        }
        return result;
    }
}
