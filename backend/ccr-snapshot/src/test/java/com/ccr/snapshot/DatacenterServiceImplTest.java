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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 数据中心监控测试(§11.7):批次概览组装、OK/STALE 时效判定
 */
@ExtendWith(MockitoExtension.class)
class DatacenterServiceImplTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private DatacenterServiceImpl datacenterService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(datacenterService, "dataStaleDays", 3);
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

        assertEquals(19, rows.size());
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
                "SELECT MAX(data_dt) FROM dw_org_performance_snapshot", String.class)).thenReturn(dataDt);
        when(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM dw_org_performance_snapshot WHERE data_dt = ?", Long.class, dataDt))
                .thenReturn(42L);
        when(jdbcTemplate.queryForObject(
                "SELECT MAX(snapshot_ts) FROM dw_org_performance_snapshot WHERE data_dt = ?",
                String.class, dataDt)).thenReturn("2026-08-07 02:00:00");

        List<Map<String, Object>> rows = datacenterService.batchOverview();

        assertEquals(19, rows.size());
        Map<String, Object> orgPerf = rows.stream()
                .filter(r -> "dw_org_performance_snapshot".equals(r.get("table"))).findFirst().orElseThrow();
        assertEquals(dataDt, orgPerf.get("latestDataDt"));
        assertEquals(42L, orgPerf.get("batchRows"));
        assertEquals("2026-08-07 02:00:00", orgPerf.get("landedTime"));

        // 无数据表:批次行数 0,无落地时间列的表 landedTime 为 null
        Map<String, Object> corp = rows.get(0);
        assertEquals(0L, corp.get("batchRows"));
        assertNull(corp.get("landedTime"));
    }
}
