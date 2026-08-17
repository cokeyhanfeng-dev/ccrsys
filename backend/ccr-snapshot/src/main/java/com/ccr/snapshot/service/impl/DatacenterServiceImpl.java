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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 数据中心监控实现(§11.7,只读)。
 * <p>落地表清单动态查询 information_schema(前缀 dw_/caps_、排除弃用表、正则白名单防注入),
 * 数仓新增/下线表无需改代码即自动纳入/剔除监控;已知表保留中文数据源说明,动态新增表回退表名。</p>
 */
@Slf4j
@Service
public class DatacenterServiceImpl implements DatacenterService {

    /** 已知数仓落地表 → 数据源中文说明(db/02,顺序即看板展示顺序;动态清单中未知表回退表名) */
    private static final Map<String, String> KNOWN_TABLE_NAMES = new LinkedHashMap<>();

    static {
        KNOWN_TABLE_NAMES.put("caps_corp_cust_basic_info", "对公客户主数据");
        KNOWN_TABLE_NAMES.put("caps_indv_cust_basic_info", "对私客户主数据");
        // 2026-08-11 去冗余:dw_own_financing_snapshot 并入 dw_loan_contract_snapshot(见下方贷款合同),不再单列
        KNOWN_TABLE_NAMES.put("dw_contribution_metric", "当前贡献度(T3)");
        KNOWN_TABLE_NAMES.put("dw_credit_report_snapshot", "征信报告头(T4)");
        KNOWN_TABLE_NAMES.put("dw_org_performance_snapshot", "机构达成情况(T5)");
        KNOWN_TABLE_NAMES.put("dw_mortgage_snapshot", "抵押物快照");
        KNOWN_TABLE_NAMES.put("dw_guarantor_snapshot", "担保人快照");
        KNOWN_TABLE_NAMES.put("dw_credit_agreement_snapshot", "授信协议快照");
        KNOWN_TABLE_NAMES.put("dw_credit_financing_summary", "他行融资概要(D20)");
        KNOWN_TABLE_NAMES.put("dw_credit_financing_detail", "他行融资明细(D20)");
        KNOWN_TABLE_NAMES.put("dw_customer_group_snapshot", "集团主数据");
        KNOWN_TABLE_NAMES.put("dw_customer_group_member_snapshot", "集团成员");
        KNOWN_TABLE_NAMES.put("dw_customer_relation_snapshot", "客户关系");
        KNOWN_TABLE_NAMES.put("dw_group_credit_snapshot", "集团综合授信");
        KNOWN_TABLE_NAMES.put("dw_member_credit_limit_snapshot", "成员授信额度");
        KNOWN_TABLE_NAMES.put("dw_loan_contract_snapshot", "贷款合同");
        KNOWN_TABLE_NAMES.put("dw_loan_note_snapshot", "借据");
        KNOWN_TABLE_NAMES.put("dw_deposit_account_snapshot", "存款账户");
    }

    /** 弃用表(物理表存在也不再纳入动态监控;dw_credit_tranche_snapshot 为已去除的用信分项层存量表) */
    private static final Set<String> DEPRECATED_TABLES = Set.of("dw_org_dim", "dw_credit_tranche_snapshot");

    /** 表名白名单:字母开头 + 字母数字下划线(防注入,与 DwTableCacheLoader 一致) */
    private static final Pattern TABLE_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");

    /** 动态查询 information_schema:当前库下数仓落地表(dw_/caps_ 前缀,LIKE 下划线转义) */
    private static final String TABLE_QUERY =
            "SELECT table_name FROM information_schema.TABLES "
                    + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' "
                    + "AND (table_name LIKE 'dw\\_%' OR table_name LIKE 'caps\\_%')";

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
        for (String table : warehouseTables()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("table", table);
            row.put("sourceName", sourceName(table));
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
        for (String table : warehouseTables()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("table", table);
            row.put("sourceName", sourceName(table));
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

    /** 动态发现数仓落地表:已知表按登记顺序,动态新增表按字母序追加;排除弃用表与非法表名 */
    private List<String> warehouseTables() {
        List<String> dynamic = jdbcTemplate.queryForList(TABLE_QUERY, String.class).stream()
                .filter(t -> t != null && TABLE_PATTERN.matcher(t).matches())
                .filter(t -> !DEPRECATED_TABLES.contains(t))
                .sorted()
                .toList();
        List<String> ordered = new ArrayList<>();
        KNOWN_TABLE_NAMES.forEach((known, name) -> {
            if (dynamic.contains(known)) {
                ordered.add(known);
            }
        });
        // 动态新增表(未知)按字母序追加在已知表之后
        dynamic.stream().filter(t -> !ordered.contains(t)).forEach(ordered::add);
        return ordered;
    }

    /** 数据源中文说明:已知表用登记名,动态新增表回退表名 */
    private String sourceName(String table) {
        return KNOWN_TABLE_NAMES.getOrDefault(table, table);
    }
}
