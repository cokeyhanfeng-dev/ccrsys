package com.ccr.snapshot;

import com.ccr.snapshot.service.impl.DatacenterServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 数据中心监控测试(§11.7):批次概览组装、OK/STALE 时效判定、动态落地表清单发现
 */
@ExtendWith(MockitoExtension.class)
class DatacenterServiceImplTest {

    /** 模拟 information_schema 动态清单:含 A11 dw_credit_agreement_snapshot、含弃用表(验证被 DEPRECATED_TABLES 过滤) */
    private static final List<String> DYNAMIC_TABLES = List.of(
            "caps_corp_cust_basic_info",
            "caps_indv_cust_basic_info",
            "dw_contribution_metric",
            "dw_credit_agreement_snapshot",
            "dw_credit_financing_detail",
            "dw_credit_financing_summary",
            "dw_credit_report_snapshot",
            "dw_customer_group_member_snapshot",
            "dw_customer_group_snapshot",
            "dw_customer_relation_snapshot",
            "dw_deposit_account_snapshot",
            "dw_group_credit_snapshot",
            "dw_guarantor_snapshot",
            "dw_loan_contract_snapshot",
            "dw_loan_note_snapshot",
            "dw_member_credit_limit_snapshot",
            "dw_mortgage_snapshot",
            "dw_org_performance_snapshot");

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private DatacenterServiceImpl datacenterService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(datacenterService, "dataStaleDays", 3);
        // 动态表清单:information_schema 查询返回真实库的 19 张 dw_/caps_ 表
        // lenient:warehouseTables_dynamicDiscovery 测试会用定制清单覆盖本 stub
        lenient().when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(DYNAMIC_TABLES);
    }

    @Test
    void sourceStatus_freshIsOk_staleAndEmptyAreStale() {
        String fresh = LocalDate.now().minusDays(1).toString();
        String stale = LocalDate.now().minusDays(10).toString();
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0, String.class);
            if (sql.contains("caps_corp_cust_basic_info")) {
                return fresh;
            }
            if (sql.contains("caps_indv_cust_basic_info")) {
                return stale;
            }
            return null; // 其余表无数据
        });

        List<Map<String, Object>> rows = datacenterService.sourceStatus();

        // 18 张 mock 表中 2 张(021 废弃 dw_org_performance_snapshot/dw_customer_relation_snapshot)被过滤
        assertEquals(16, rows.size());
        assertTrue(rows.stream().noneMatch(r -> "dw_customer_relation_snapshot".equals(r.get("table"))
                        || "dw_org_performance_snapshot".equals(r.get("table"))),
                "废弃表不应纳入监控");
        Map<String, Object> corp = rows.get(0);
        assertEquals("caps_corp_cust_basic_info", corp.get("table"));
        assertEquals("对公客户主数据", corp.get("sourceName"));
        assertEquals(1L, corp.get("delayDays"));
        assertEquals(3, corp.get("thresholdDays"));
        assertEquals("OK", corp.get("status"));

        Map<String, Object> indv = rows.get(1);
        assertEquals(10L, indv.get("delayDays"));
        assertEquals("STALE", indv.get("status"));

        // 无数据:STALE,delayDays 为 null
        Map<String, Object> empty = rows.get(2);
        assertNull(empty.get("delayDays"));
        assertEquals("STALE", empty.get("status"));
    }

    @Test
    void batchOverview_assemblesLatestBatchRows() {
        String dataDt = LocalDate.now().toString();
        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn(null);
        when(jdbcTemplate.queryForObject(
                "SELECT MAX(data_dt) FROM dw_contribution_metric", String.class)).thenReturn(dataDt);
        when(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM dw_contribution_metric WHERE data_dt = ?", Long.class, dataDt))
                .thenReturn(42L);

        List<Map<String, Object>> rows = datacenterService.batchOverview();

        assertEquals(16, rows.size());
        // 021 废弃表(dw_org_performance_snapshot/dw_customer_relation_snapshot)不再纳入批次监控
        assertFalse(rows.stream().anyMatch(r -> "dw_org_performance_snapshot".equals(r.get("table"))
                        || "dw_customer_relation_snapshot".equals(r.get("table"))),
                "废弃表不应在批次监控中");
        // 有数据表:批次行数=COUNT,latestDataDt 落库,落地时间列为空(021 后活跃表均无 snapshot_ts 列)
        Map<String, Object> contrib = rows.stream()
                .filter(r -> "dw_contribution_metric".equals(r.get("table"))).findFirst().orElseThrow();
        assertEquals(dataDt, contrib.get("latestDataDt"));
        assertEquals(42L, contrib.get("batchRows"));
        assertNull(contrib.get("landedTime"));

        // 无数据表:批次行数 0,落地时间为 null
        Map<String, Object> corp = rows.get(0);
        assertEquals(0L, corp.get("batchRows"));
        assertNull(corp.get("landedTime"));
    }

    @Test
    void warehouseTables_dynamicDiscoveryIncludesNewAndExcludesDeprecated() {
        // 模拟数仓新增一张表 + 弃用表仍在库,且顺序杂乱(验证按已知表顺序归位、新表字母序追加)
        List<String> dynamic = List.of(
                "dw_credit_agreement_snapshot",
                "dw_org_dim",
                "dw_customer_relation_snapshot",
                "dw_org_performance_snapshot",
                "dw_new_landed_snapshot",
                "caps_corp_cust_basic_info");
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(dynamic);
        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(String.class))).thenReturn(null);

        List<Map<String, Object>> rows = datacenterService.sourceStatus();

        List<String> tables = rows.stream().map(r -> (String) r.get("table")).toList();
        // 新增表(未知)自动纳入,按字母序追加在已知表后
        assertTrue(tables.contains("dw_new_landed_snapshot"), "动态新增表应自动纳入监控");
        assertEquals("dw_new_landed_snapshot", tables.get(tables.size() - 1));
        // 弃用表 dw_org_dim 不在库清单时自然不出现;在库也应被过滤
        assertFalse(tables.contains("dw_org_dim"), "弃用表 dw_org_dim 不应纳入监控");
        // 增量021废弃表同样被 DEPRECATED_TABLES 过滤
        assertFalse(tables.contains("dw_customer_relation_snapshot"), "021废弃表不应纳入监控");
        assertFalse(tables.contains("dw_org_performance_snapshot"), "021废弃表不应纳入监控");
        // 新增表无中文说明,回退表名
        Map<String, Object> news = rows.stream()
                .filter(r -> "dw_new_landed_snapshot".equals(r.get("table"))).findFirst().orElseThrow();
        assertEquals("dw_new_landed_snapshot", news.get("sourceName"));
        // 已知表按登记顺序归位(caps 前缀表仍在最前)
        assertEquals("caps_corp_cust_basic_info", rows.get(0).get("table"));
        assertEquals("dw_credit_agreement_snapshot", rows.get(1).get("table"));
    }
}
