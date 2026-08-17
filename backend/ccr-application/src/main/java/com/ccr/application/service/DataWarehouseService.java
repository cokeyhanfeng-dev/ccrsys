package com.ccr.application.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数仓只读查询(db/02 外部数据落地表;etl_md5+data_dt 批次,业务读取最新 data_dt 成功批次)
 * 仅读不写;供申请创建回填、提交编排采集快照、集团/成员/客户视图查询共用
 */
@Service
public class DataWarehouseService {

    @Resource
    private JdbcTemplate jdbcTemplate;

    // ---------- 批次 ----------

    /** 单表最新数据日期(无数据返回 null) */
    public String latestDataDt(String table) {
        return jdbcTemplate.queryForObject("SELECT MAX(data_dt) FROM " + table, String.class);
    }

    /** 多表最新数据日期(表名→data_dt;无数据的表不返回) */
    public Map<String, String> latestDataDates(Collection<String> tables) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : tables) {
            String dt = latestDataDt(table);
            if (dt != null && !dt.isBlank()) {
                result.put(table, dt);
            }
        }
        return result;
    }

    // ---------- 客户主数据 ----------

    /** 对公客户(最新批次) */
    public Map<String, Object> findCorpCustomer(String customerNo) {
        return queryOne("""
                SELECT * FROM caps_corp_cust_basic_info
                WHERE cust_no = ? AND data_dt = (SELECT MAX(d2.data_dt) FROM caps_corp_cust_basic_info d2 WHERE d2.cust_no = caps_corp_cust_basic_info.cust_no)""", customerNo);
    }

    /** 对私客户(最新批次) */
    public Map<String, Object> findIndvCustomer(String customerNo) {
        return queryOne("""
                SELECT * FROM caps_indv_cust_basic_info
                WHERE cust_no = ? AND data_dt = (SELECT MAX(d2.data_dt) FROM caps_indv_cust_basic_info d2 WHERE d2.cust_no = caps_indv_cust_basic_info.cust_no)""", customerNo);
    }

    /** 本行融资/存量贷款(贷款合同,最新批次;2026-08-11 去冗余:原 dw_own_financing 并入贷款合同) */
    public List<Map<String, Object>> ownFinancing(String customerNo) {
        return jdbcTemplate.queryForList("""
                SELECT contract_no contractNo, agreement_no agreementNo, borrower_customer_no borrowerCustomerNo,
                       contract_amount contractAmount, contract_balance loanBalance, guarantee_type guaranteeType,
                       execution_rate contractRate, currency, start_date startDate, maturity_date maturityDate,
                       contract_status contractStatus
                FROM dw_loan_contract_snapshot
                WHERE borrower_customer_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_loan_contract_snapshot)
                ORDER BY contract_no""", customerNo);
    }

    /** 当前贡献度(T3,最新批次) */
    public List<Map<String, Object>> contribution(String customerNo) {
        return jdbcTemplate.queryForList("""
                SELECT * FROM dw_contribution_metric
                WHERE cust_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_contribution_metric)""", customerNo);
    }

    /** 抵押物快照(最新批次) */
    public List<Map<String, Object>> mortgages(String customerNo) {
        return jdbcTemplate.queryForList("""
                SELECT * FROM dw_mortgage_snapshot
                WHERE cust_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_mortgage_snapshot)""", customerNo);
    }

    /** 担保人快照(最新批次) */
    public List<Map<String, Object>> guarantors(String customerNo) {
        return jdbcTemplate.queryForList("""
                SELECT * FROM dw_guarantor_snapshot
                WHERE cust_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_guarantor_snapshot)""", customerNo);
    }

    // ---------- 集团链 ----------

    /** 集团主数据(最新批次) */
    public Map<String, Object> findGroup(String groupNo) {
        return queryOne("""
                SELECT * FROM dw_customer_group_snapshot
                WHERE group_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_customer_group_snapshot)""", groupNo);
    }

    /** 集团联想(最新批次;按集团号/集团名模糊,申请页下拉,前 10 条) */
    public List<Map<String, Object>> suggestGroups(String keyword) {
        return jdbcTemplate.queryForList("""
                SELECT group_no, group_name FROM dw_customer_group_snapshot
                WHERE (group_no LIKE ? OR group_name LIKE ?)
                  AND data_dt = (SELECT MAX(data_dt) FROM dw_customer_group_snapshot)
                ORDER BY group_no LIMIT 10""", "%" + keyword + "%", "%" + keyword + "%");
    }

    /** 集团成员(最新批次;relation_end 空或未到期=在团) */
    public List<Map<String, Object>> groupMembers(String groupNo) {
        return jdbcTemplate.queryForList("""
                SELECT * FROM dw_customer_group_member_snapshot
                WHERE group_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_customer_group_member_snapshot)
                ORDER BY member_customer_no""", groupNo);
    }

    /** 集团成员单条(最新批次) */
    public Map<String, Object> findGroupMember(String groupNo, String memberCustomerNo) {
        return queryOne("""
                SELECT * FROM dw_customer_group_member_snapshot
                WHERE group_no = ? AND member_customer_no = ?
                  AND data_dt = (SELECT MAX(data_dt) FROM dw_customer_group_member_snapshot)""",
                groupNo, memberCustomerNo);
    }

    /** 集团授信(最新批次,有效优先) */
    public Map<String, Object> findGroupCredit(String groupNo) {
        return queryOne("""
                SELECT * FROM dw_group_credit_snapshot
                WHERE group_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_group_credit_snapshot)
                ORDER BY CASE credit_status WHEN 'EFFECTIVE' THEN 0 ELSE 1 END, etl_md5 DESC
                LIMIT 1""", groupNo);
    }

    /** 集团下全部成员额度(最新批次) */
    public List<Map<String, Object>> memberLimitsByGroup(String groupCreditNo) {
        return jdbcTemplate.queryForList("""
                SELECT * FROM dw_member_credit_limit_snapshot
                WHERE group_credit_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_member_credit_limit_snapshot)
                ORDER BY member_customer_no""", groupCreditNo);
    }

    /** 成员额度单条(按集团授信+成员,最新批次,有效优先) */
    public Map<String, Object> findMemberLimit(String groupCreditNo, String memberCustomerNo) {
        return queryOne("""
                SELECT * FROM dw_member_credit_limit_snapshot
                WHERE group_credit_no = ? AND member_customer_no = ?
                  AND data_dt = (SELECT MAX(data_dt) FROM dw_member_credit_limit_snapshot)
                ORDER BY CASE limit_status WHEN 'EFFECTIVE' THEN 0 ELSE 1 END, etl_md5 DESC
                LIMIT 1""", groupCreditNo, memberCustomerNo);
    }

    /** 成员额度(按成员客户号,最新批次) */
    public List<Map<String, Object>> memberLimitsByMember(String memberCustomerNo) {
        return jdbcTemplate.queryForList("""
                SELECT * FROM dw_member_credit_limit_snapshot
                WHERE member_customer_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_member_credit_limit_snapshot)
                ORDER BY member_limit_no""", memberCustomerNo);
    }

    /** 授信协议(按客户,最新批次;单户与集团成员统一按 customer_no) */
    public List<Map<String, Object>> creditAgreements(String customerNo) {
        return jdbcTemplate.queryForList("""
                SELECT * FROM dw_credit_agreement_snapshot
                WHERE customer_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_credit_agreement_snapshot)
                ORDER BY agreement_no""", customerNo);
    }

    /** 贷款合同(按借款人,最新批次) */
    public List<Map<String, Object>> contractsByBorrower(String customerNo) {
        return jdbcTemplate.queryForList("""
                SELECT * FROM dw_loan_contract_snapshot
                WHERE borrower_customer_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_loan_contract_snapshot)
                ORDER BY contract_no""", customerNo);
    }

    /** 贷款合同单条(按合同号,最新批次,版本最新优先) */
    public Map<String, Object> findContract(String contractNo) {
        return queryOne("""
                SELECT * FROM dw_loan_contract_snapshot
                WHERE contract_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_loan_contract_snapshot)
                ORDER BY contract_version DESC, etl_md5 DESC
                LIMIT 1""", contractNo);
    }

    /** 借据(按合同,最新批次) */
    public List<Map<String, Object>> notesByContract(String contractNo) {
        return jdbcTemplate.queryForList("""
                SELECT * FROM dw_loan_note_snapshot
                WHERE contract_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_loan_note_snapshot)
                ORDER BY loan_note_no""", contractNo);
    }

    // ---------- 存款 ----------

    /** 存款账户(按客户,最新批次) */
    public List<Map<String, Object>> depositAccounts(String customerNo) {
        return jdbcTemplate.queryForList("""
                SELECT * FROM dw_deposit_account_snapshot
                WHERE customer_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_deposit_account_snapshot)
                ORDER BY open_date""", customerNo);
    }

    /** 存款账户单条(按明文账号,最新批次) */
    public Map<String, Object> findDepositAccountByNo(String depositAccountNo) {
        return queryOne("""
                SELECT * FROM dw_deposit_account_snapshot
                WHERE deposit_account_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_deposit_account_snapshot)
                ORDER BY etl_md5 DESC
                LIMIT 1""", depositAccountNo);
    }

    // ---------- 私有 ----------

    /**
     * 申请相关的数仓数据集(提交比对基线/快照采集范围)
     * 集团:集团链全量;单户:客户主数据+贷款合同+贡献度+征信;存款附加存款账户
     */
    public static List<String> relevantDatasets(String businessType, String customerScope) {
        List<String> tables = new java.util.ArrayList<>();
        if ("GROUP".equals(customerScope)) {
            tables.add("dw_customer_group_snapshot");
            tables.add("dw_customer_group_member_snapshot");
            tables.add("dw_group_credit_snapshot");
            tables.add("dw_member_credit_limit_snapshot");
            tables.add("dw_loan_contract_snapshot");
            tables.add("dw_loan_note_snapshot");
        } else if ("INDIVIDUAL".equals(customerScope)) {
            tables.add("caps_indv_cust_basic_info");
            tables.add("dw_loan_contract_snapshot");
            tables.add("dw_credit_report_snapshot");
            tables.add("dw_credit_financing_summary");
            tables.add("dw_credit_financing_detail");
        } else {
            tables.add("caps_corp_cust_basic_info");
            tables.add("dw_loan_contract_snapshot");
            tables.add("dw_credit_report_snapshot");
            tables.add("dw_credit_financing_summary");
            tables.add("dw_credit_financing_detail");
        }
        tables.add("dw_contribution_metric");
        if ("DEPOSIT".equals(businessType)) {
            tables.add("dw_deposit_account_snapshot");
        }
        return tables;
    }

    private Map<String, Object> queryOne(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 数仓行来源数据日期(data_dt → LocalDate) */
    public static LocalDate rowDataDt(Map<String, Object> row) {
        Object dt = row == null ? null : row.get("data_dt");
        if (dt == null) {
            return null;
        }
        if (dt instanceof java.util.Date d) {
            return new java.sql.Date(d.getTime()).toLocalDate();
        }
        return LocalDate.parse(String.valueOf(dt).substring(0, 10));
    }
}
