package com.ccr.snapshot.service.impl;

import com.ccr.snapshot.service.DatacenterService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据中心监控实现(§11.7,只读;表清单枚举自 db/02_external_data.sql)
 */
@Slf4j
@Service
public class DatacenterServiceImpl implements DatacenterService {

    /** 数仓落地表 → 数据源名(db/02,顺序即看板展示顺序) */
    private static final Map<String, String> WAREHOUSE_TABLES = new LinkedHashMap<>();

    static {
        WAREHOUSE_TABLES.put("caps_corp_cust_basic_info", "对公客户主数据");
        WAREHOUSE_TABLES.put("caps_indv_cust_basic_info", "对私客户主数据");
        WAREHOUSE_TABLES.put("dw_own_financing_snapshot", "本行融资(T2)");
        WAREHOUSE_TABLES.put("dw_contribution_metric", "当前贡献度(T3)");
        WAREHOUSE_TABLES.put("dw_credit_report_snapshot", "征信报告头(T4)");
        WAREHOUSE_TABLES.put("dw_org_performance_snapshot", "机构达成情况(T5)");
        WAREHOUSE_TABLES.put("dw_mortgage_snapshot", "抵押物快照");
        WAREHOUSE_TABLES.put("dw_guarantor_snapshot", "担保人快照");
        WAREHOUSE_TABLES.put("dw_credit_financing_summary", "他行融资概要(D20)");
        WAREHOUSE_TABLES.put("dw_credit_financing_detail", "他行融资明细(D20)");
        WAREHOUSE_TABLES.put("dw_customer_group_snapshot", "集团主数据");
        WAREHOUSE_TABLES.put("dw_customer_group_member_snapshot", "集团成员");
        WAREHOUSE_TABLES.put("dw_customer_relation_snapshot", "客户关系");
        WAREHOUSE_TABLES.put("dw_group_credit_snapshot", "集团综合授信");
        WAREHOUSE_TABLES.put("dw_member_credit_limit_snapshot", "成员授信额度");
        WAREHOUSE_TABLES.put("dw_credit_tranche_snapshot", "用信分项");
        WAREHOUSE_TABLES.put("dw_loan_contract_snapshot", "贷款合同");
        WAREHOUSE_TABLES.put("dw_loan_note_snapshot", "借据");
        WAREHOUSE_TABLES.put("dw_deposit_account_snapshot", "存款账户");
    }

    /** 带落地时间戳列的表(其余表无该列,landedTime 返回 null) */
    private static final Map<String, String> LANDED_TS_COLUMN = Map.of(
            "dw_org_performance_snapshot", "snapshot_ts");

    /** 数据时效容忍天数(与快照质量规则同一配置,默认 3 个自然日) */
    @Value("${ccr.snapshot.data-stale-days:3}")
    private int dataStaleDays;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public List<Map<String, Object>> batchOverview() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : WAREHOUSE_TABLES.entrySet()) {
            String table = entry.getKey();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("table", table);
            row.put("sourceName", entry.getValue());
            String latestDataDt = latestDataDt(table);
            row.put("latestDataDt", latestDataDt);
            row.put("batchRows", latestDataDt == null ? 0L : jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM " + table + " WHERE data_dt = ?", Long.class, latestDataDt));
            String tsColumn = LANDED_TS_COLUMN.get(table);
            row.put("landedTime", tsColumn == null ? null : jdbcTemplate.queryForObject(
                    "SELECT MAX(" + tsColumn + ") FROM " + table + " WHERE data_dt = ?",
                    String.class, latestDataDt));
            result.add(row);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> sourceStatus() {
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : WAREHOUSE_TABLES.entrySet()) {
            String table = entry.getKey();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("table", table);
            row.put("sourceName", entry.getValue());
            String latestDataDt = latestDataDt(table);
            row.put("latestDataDt", latestDataDt);
            Long delayDays = null;
            if (latestDataDt != null) {
                delayDays = ChronoUnit.DAYS.between(LocalDate.parse(latestDataDt.substring(0, 10)), today);
            }
            row.put("delayDays", delayDays);
            row.put("thresholdDays", dataStaleDays);
            boolean stale = delayDays == null || delayDays > dataStaleDays;
            row.put("status", stale ? "STALE" : "OK");
            result.add(row);
        }
        return result;
    }

    /** 单表最新数据日期(无数据返回 null) */
    private String latestDataDt(String table) {
        return jdbcTemplate.queryForObject("SELECT MAX(data_dt) FROM " + table, String.class);
    }
}
