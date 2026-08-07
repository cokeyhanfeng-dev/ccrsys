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
                WHERE cust_no = ? AND data_dt = (SELECT MAX(data_dt) FROM caps_corp_cust_basic_info)""", customerNo);
    }

    /** 对私客户(最新批次) */
    public Map<String, Object> findIndvCustomer(String customerNo) {
        return queryOne("""
                SELECT * FROM caps_indv_cust_basic_info
                WHERE cust_no = ? AND data_dt = (SELECT MAX(data_dt) FROM caps_indv_cust_basic_info)""", customerNo);
    }

    /** 本行融资(T2,最新批次) */
    public List<Map<String, Object>> ownFinancing(String customerNo) {
        return jdbcTemplate.queryForList("""
                SELECT * FROM dw_own_financing_snapshot
                WHERE cust_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_own_financing_snapshot)""", customerNo);
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

    /** 用信分项(按成员额度,最新批次) */
    public List<Map<String, Object>> tranchesByLimit(String memberLimitNo) {
        return jdbcTemplate.queryForList("""
                SELECT * FROM dw_credit_tranche_snapshot
                WHERE member_limit_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_credit_tranche_snapshot)
                ORDER BY tranche_no""", memberLimitNo);
    }

    /** 用信分项(按成员客户号,最新批次) */
    public List<Map<String, Object>> tranchesByMember(String memberCustomerNo) {
        return jdbcTemplate.queryForList("""
                SELECT * FROM dw_credit_tranche_snapshot
                WHERE member_customer_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_credit_tranche_snapshot)
                ORDER BY tranche_no""", memberCustomerNo);
    }

    /** 贷款合同(按用信分项,最新批次) */
    public List<Map<String, Object>> contractsByTranche(String trancheNo) {
        return jdbcTemplate.queryForList("""
                SELECT * FROM dw_loan_contract_snapshot
                WHERE tranche_no = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_loan_contract_snapshot)
                ORDER BY contract_no""", trancheNo);
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

    /** 存款账户单条(按查询哈希,最新批次) */
    public Map<String, Object> findDepositAccountByHash(String depositAccountHash) {
        return queryOne("""
                SELECT * FROM dw_deposit_account_snapshot
                WHERE deposit_account_hash = ? AND data_dt = (SELECT MAX(data_dt) FROM dw_deposit_account_snapshot)
                ORDER BY etl_md5 DESC
                LIMIT 1""", depositAccountHash);
    }

    // ---------- 私有 ----------

    /**
     * 申请相关的数仓数据集(提交比对基线/快照采集范围)
     * 集团:集团链全量;单户:客户主数据+本行融资+贡献度+征信;存款附加存款账户
     */
    public static List<String> relevantDatasets(String businessType, String customerScope) {
        List<String> tables = new java.util.ArrayList<>();
        if ("GROUP".equals(customerScope)) {
            tables.add("dw_customer_group_snapshot");
            tables.add("dw_customer_group_member_snapshot");
            tables.add("dw_group_credit_snapshot");
            tables.add("dw_member_credit_limit_snapshot");
            tables.add("dw_credit_tranche_snapshot");
            tables.add("dw_loan_contract_snapshot");
            tables.add("dw_loan_note_snapshot");
        } else if ("INDIVIDUAL".equals(customerScope)) {
            tables.add("caps_indv_cust_basic_info");
            tables.add("dw_own_financing_snapshot");
            tables.add("dw_credit_report_snapshot");
            tables.add("dw_credit_financing_summary");
            tables.add("dw_credit_financing_detail");
        } else {
            tables.add("caps_corp_cust_basic_info");
            tables.add("dw_own_financing_snapshot");
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
