package com.ccr.application;

import com.ccr.application.support.CommitmentBaselineResolver;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用实际基线/归并代码，只替换数据源为虚构客户。 */
class CommitmentBaselineConsistencyTest {
    static final String PAYROLL = "PUBLIC_PAYROLL_CONTRIBUTION";
    static class Warehouse extends JdbcTemplate {
        boolean relatedSaved = true;
        List<Map<String, Object>> main = new ArrayList<>();
        List<Map<String, Object>> related = new ArrayList<>(List.of(metric(PAYROLL, "253", "CONTRIBUTION_AMOUNT", "20260922")));
        @Override public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("ccr_application_related_person"))
                return relatedSaved ? new ArrayList<>(List.of(new HashMap<>(Map.of("relatedCustomerNo", "FAKE_RELATED")))) : new ArrayList<>();
            if (sql.contains("dw_contribution_metric")) return new ArrayList<>(main);
            throw new AssertionError(sql);
        }
        @Override public List<Map<String, Object>> queryForList(String sql) {
            if (sql.contains("metric_code metricCode") && sql.contains("ccr_metric_definition")) return List.of(
                    new HashMap<>(Map.of("metricCode", PAYROLL, "metricName", "代发户数", "valueType", "CONTRIBUTION_AMOUNT")),
                    new HashMap<>(Map.of("metricCode", "RATIO_TEST", "metricName", "比例", "valueType", "RATIO")));
            if (sql.contains("ccr_metric_definition")) return List.of(
                    Map.of("metric_code", PAYROLL, "metric_name", "代发户数"),
                    Map.of("metric_code", "RATIO_TEST", "metric_name", "比例"));
            if (sql.contains("dw_contribution_metric")) return new ArrayList<>(related);
            throw new AssertionError(sql);
        }
    }
    static Map<String, Object> metric(String code, String value, String type, String date) {
        return new HashMap<>(Map.of("custNo", "FAKE_RELATED", "metricCode", code, "metricValue", new BigDecimal(value),
                "valueType", type, "dataDt", date));
    }
    static BigDecimal displayed(Warehouse db, String code, String customer, String group) {
        return (BigDecimal) CommitmentBaselineResolver.loadContribution(db, customer, group).stream()
                .filter(row -> code.equals(row.get("metricCode"))).findFirst().orElseThrow().get("metricValue");
    }
    @Test void relatedOnlyMetricAppearsAndMatchesSubmission253() {
        Warehouse db = new Warehouse();
        assertEquals(new BigDecimal("253"), displayed(db, PAYROLL, "FAKE_MAIN", null));
        assertEquals(displayed(db, PAYROLL, "FAKE_MAIN", null),
                CommitmentBaselineResolver.resolveBaseline(db, PAYROLL, "FAKE_MAIN", null));
        assertTrue(new BigDecimal("50").compareTo(displayed(db, PAYROLL, "FAKE_MAIN", null)) < 0);
    }
    @Test void latestRelatedBatchWinsRegardlessOfInputOrderAndFoldedTypeWinsWithinBatch() {
        Warehouse db = new Warehouse();
        db.related = List.of(metric(PAYROLL, "10", "CONTRIBUTION_AMOUNT", "20260920"),
                metric(PAYROLL, "300", "AVG_BALANCE", "20260922"),
                metric(PAYROLL, "253", "CONTRIBUTION_AMOUNT", "20260922"));
        assertEquals(new BigDecimal("253"), displayed(db, PAYROLL, "FAKE_MAIN", null));
        db.related = List.of(metric(PAYROLL, "7", "CONTRIBUTION_AMOUNT", "20260920"),
                metric(PAYROLL, "20", "AVG_BALANCE", "20260922"));
        assertEquals(new BigDecimal("20"), displayed(db, PAYROLL, "FAKE_MAIN", null));
    }
    @Test void absentMetricsAreZeroAndOtherAndDisabledHaveNoBaseline() {
        Warehouse db = new Warehouse(); db.relatedSaved = false;
        assertEquals(BigDecimal.ZERO, displayed(db, PAYROLL, "FAKE_MAIN", null));
        assertNull(CommitmentBaselineResolver.resolveBaseline(db, "OTHER", "FAKE_MAIN", null));
        assertNull(CommitmentBaselineResolver.resolveBaseline(db, "DISABLED", "FAKE_MAIN", null));
    }
    @Test void ratioAndGroupDoNotMergeRelatedAndMainUsesSameBatchTypeSelection() {
        Warehouse db = new Warehouse();
        db.main = List.of(metric("RATIO_TEST", "65", "RATIO", "20260922"),
                metric(PAYROLL, "9", "AVG_BALANCE", "20260922"),
                metric(PAYROLL, "5", "CONTRIBUTION_AMOUNT", "20260922"));
        db.related = List.of(metric("RATIO_TEST", "99", "RATIO", "20260922"),
                metric(PAYROLL, "253", "CONTRIBUTION_AMOUNT", "20260922"));
        assertEquals(new BigDecimal("65"), displayed(db, "RATIO_TEST", "FAKE_MAIN", null));
        assertEquals(new BigDecimal("258"), displayed(db, PAYROLL, "FAKE_MAIN", null));
        assertEquals(new BigDecimal("5"), displayed(db, PAYROLL, null, "FAKE_GROUP"));
    }
    @Test void repeatedReadsDoNotAccumulateRelatedValues() {
        Warehouse db = new Warehouse();
        assertEquals(displayed(db, PAYROLL, "FAKE_MAIN", null), displayed(db, PAYROLL, "FAKE_MAIN", null));
    }
    @Test void submissionRejectsLowerOrEqualTargetsAndUpdatesValidCommitmentBaseline() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                com.ccr.application.domain.CcrApplicationCommitment.class);
        Warehouse db = new Warehouse();
        var service = new com.ccr.application.service.impl.ApplicationSubmitServiceImpl();
        var mapper = org.mockito.Mockito.mock(com.ccr.application.mapper.CcrApplicationCommitmentMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "jdbcTemplate", db);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "commitmentMapper", mapper);
        var app = new com.ccr.application.domain.CcrApplication();
        app.setId(1L); app.setCustomerNo("FAKE_MAIN");
        var commitment = new com.ccr.application.domain.CcrApplicationCommitment();
        commitment.setId(2L); commitment.setMetricCode(PAYROLL); commitment.setBaselineValue(BigDecimal.ZERO);
        org.mockito.Mockito.when(mapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(commitment));
        for (String target : List.of("50", "253")) {
            commitment.setTargetValue(new BigDecimal(target));
            var error = assertThrows(com.ccr.common.exception.ServiceException.class,
                    () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "recalcCommitmentBaselines", app));
            assertTrue(error.getMessage().contains("提交时基线 253"));
            assertFalse(error.getMessage().contains("数仓已更新"));
        }
        commitment.setTargetValue(new BigDecimal("300"));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "recalcCommitmentBaselines", app);
        org.mockito.Mockito.verify(mapper).update(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
    }

}
