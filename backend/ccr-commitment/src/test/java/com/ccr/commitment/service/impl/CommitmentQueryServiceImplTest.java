package com.ccr.commitment.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ccr.commitment.domain.CcrCommitmentMetric;
import com.ccr.commitment.domain.CcrCommitmentPlan;
import com.ccr.commitment.domain.CcrTrackingEvaluation;
import com.ccr.commitment.mapper.CcrCommitmentMetricMapper;
import com.ccr.commitment.mapper.CcrCommitmentPlanMapper;
import com.ccr.commitment.mapper.CcrTrackingEvaluationMapper;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 承诺查询测试(§11.8):计划详情/月报聚合/机构达成率(D19),数据权限口径同 listPlans
 */
@ExtendWith(MockitoExtension.class)
class CommitmentQueryServiceImplTest {

    @Mock
    private CcrCommitmentPlanMapper planMapper;
    @Mock
    private CcrCommitmentMetricMapper metricMapper;
    @Mock
    private CcrTrackingEvaluationMapper evaluationMapper;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private CommitmentQueryServiceImpl queryService;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CcrCommitmentPlan.class);
        TableInfoHelper.initTableInfo(assistant, CcrCommitmentMetric.class);
        TableInfoHelper.initTableInfo(assistant, CcrTrackingEvaluation.class);
    }

    private MockedStatic<StpUtil> mockLogin(long userId, String roleCode) {
        MockedStatic<StpUtil> stp = Mockito.mockStatic(StpUtil.class);
        stp.when(StpUtil::getLoginIdAsLong).thenReturn(userId);
        lenient().when(jdbcTemplate.queryForList(contains("FROM ccr_sys_user"), eq(userId)))
                .thenReturn(List.of(Map.of("roleCode", roleCode)));
        return stp;
    }

    private CcrCommitmentPlan plan() {
        CcrCommitmentPlan plan = new CcrCommitmentPlan();
        plan.setId(1L);
        plan.setPlanNo("CMP001");
        plan.setResolutionId(500L);
        plan.setScopeType("CORPORATE_SINGLE");
        plan.setCustomerNo("C001");
        plan.setStartDate(LocalDate.now());
        plan.setEndDate(LocalDate.now().plusYears(1));
        return plan;
    }

    // ---------- 计划详情 ----------

    @Test
    @SuppressWarnings("unchecked")
    void planDetail_returnsPlanMetricsAndLatestEvaluation() {
        try (MockedStatic<StpUtil> ignored = mockLogin(1L, "admin")) {
            when(planMapper.selectById(1L)).thenReturn(plan());
            when(jdbcTemplate.queryForObject(contains("FROM ccr_commitment_plan cp"),
                    eq(Long.class), any(Object[].class))).thenReturn(1L);
            CcrCommitmentMetric metric = new CcrCommitmentMetric();
            metric.setId(11L);
            metric.setPlanId(1L);
            metric.setMetricCode("DEPOSIT_BALANCE");
            when(metricMapper.selectList(any(Wrapper.class))).thenReturn(List.of(metric));
            CcrTrackingEvaluation evaluation = new CcrTrackingEvaluation();
            evaluation.setId(21L);
            evaluation.setMetricId(11L);
            evaluation.setDataDt(LocalDate.of(2026, 8, 6));
            evaluation.setAchievementRatio(new BigDecimal("0.85"));
            evaluation.setResultStatus("TRACKING");
            when(evaluationMapper.selectOne(any(Wrapper.class))).thenReturn(evaluation);

            Map<String, Object> detail = queryService.planDetail(1L);

            assertEquals("CMP001", ((CcrCommitmentPlan) detail.get("plan")).getPlanNo());
            List<Map<String, Object>> items = (List<Map<String, Object>>) detail.get("items");
            assertEquals(1, items.size());
            assertEquals("DEPOSIT_BALANCE", ((CcrCommitmentMetric) items.get(0).get("metric")).getMetricCode());
            assertEquals("TRACKING",
                    ((CcrTrackingEvaluation) items.get(0).get("latestEvaluation")).getResultStatus());
        }
    }

    @Test
    void planDetail_notFound_throwsNotFound() {
        try (MockedStatic<StpUtil> ignored = mockLogin(1L, "admin")) {
            when(planMapper.selectById(99L)).thenReturn(null);
            ServiceException e = assertThrows(ServiceException.class, () -> queryService.planDetail(99L));
            assertEquals(ErrorCode.NOT_FOUND.getCode(), e.getCode());
        }
    }

    @Test
    void planDetail_outOfScope_throwsForbidden() {
        try (MockedStatic<StpUtil> ignored = mockLogin(2L, "customer_manager")) {
            when(planMapper.selectById(1L)).thenReturn(plan());
            when(jdbcTemplate.queryForObject(contains("FROM ccr_commitment_plan cp"),
                    eq(Long.class), any(Object[].class))).thenReturn(0L);
            ServiceException e = assertThrows(ServiceException.class, () -> queryService.planDetail(1L));
            assertEquals(ErrorCode.FORBIDDEN.getCode(), e.getCode());
        }
    }

    // ---------- 月报 ----------

    @Test
    void monthlyReport_invalidMonth_rejected() {
        try (MockedStatic<StpUtil> ignored = mockLogin(1L, "admin")) {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> queryService.monthlyReport("2026-13", null));
            assertEquals(ErrorCode.BAD_REQUEST.getCode(), e.getCode());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void monthlyReport_aggregatesByMonthAndOrg() {
        try (MockedStatic<StpUtil> ignored = mockLogin(1L, "admin")) {
            Map<String, Object> summary = new HashMap<>();
            summary.put("planCount", 3L);
            summary.put("evaluationCount", 6L);
            summary.put("avgAchievementRatio", new BigDecimal("0.90"));
            when(jdbcTemplate.queryForMap(contains("COUNT(DISTINCT te.plan_id)"), any(Object[].class)))
                    .thenReturn(summary);
            when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenAnswer(inv -> {
                String sql = inv.getArgument(0, String.class);
                if (sql.contains("risk_level")) {
                    return List.of(Map.of("riskLevel", "GREEN", "evaluationCount", 5L),
                            Map.of("riskLevel", "RED", "evaluationCount", 1L));
                }
                return List.of(Map.of("resultStatus", "TRACKING", "evaluationCount", 6L));
            });

            Map<String, Object> report = queryService.monthlyReport("2026-08", 100L);

            assertEquals("2026-08", report.get("month"));
            assertEquals(100L, report.get("orgId"));
            assertEquals(3L, report.get("planCount"));
            assertEquals(new BigDecimal("0.90"), report.get("avgAchievementRatio"));
            List<Map<String, Object>> risk = (List<Map<String, Object>>) report.get("riskDistribution");
            assertEquals(2, risk.size());
            assertEquals("RED", risk.get(1).get("riskLevel"));
        }
    }

    // ---------- 机构达成率(D19) ----------

    @Test
    @SuppressWarnings("unchecked")
    void orgAchievement_assemblesDwSnapshotAndEvaluationStats() {
        try (MockedStatic<StpUtil> ignored = mockLogin(1L, "admin")) {
            when(jdbcTemplate.queryForList(contains("caps_corp_cust_basic_info"), eq("C001")))
                    .thenReturn(List.of(Map.of("openact_org_no", "ORG01", "openact_org_nm", "城东支行")));
            when(jdbcTemplate.queryForList(contains("dw_org_performance_snapshot"), eq("ORG01")))
                    .thenReturn(List.of(Map.of(
                            "data_dt", "2026-08-06", "stat_month", "202608",
                            "achieved_amount", new BigDecimal("800"),
                            "expected_amount", new BigDecimal("1000"),
                            "completion_rate", new BigDecimal("0.8000"))));
            when(jdbcTemplate.queryForMap(anyString(), any(Object[].class))).thenAnswer(inv -> {
                String sql = inv.getArgument(0, String.class);
                Map<String, Object> stats = new HashMap<>();
                stats.put("planCount", sql.contains("ccr_sys_dept") ? 4L : 1L);
                stats.put("avgAchievementRatio", new BigDecimal("0.85"));
                return stats;
            });

            Map<String, Object> result = queryService.orgAchievement("C001");

            assertEquals("C001", result.get("customerNo"));
            assertEquals("ORG01", result.get("orgCode"));
            assertEquals("城东支行", result.get("orgName"));
            assertEquals("202608", result.get("statMonth"));
            assertEquals(new BigDecimal("0.8000"), result.get("completionRate"));
            assertEquals(4L, ((Map<String, Object>) result.get("orgPlanStats")).get("planCount"));
            assertEquals(1L, ((Map<String, Object>) result.get("customerPlanStats")).get("planCount"));
        }
    }

    @Test
    void orgAchievement_customerMissing_throwsNotFound() {
        try (MockedStatic<StpUtil> ignored = mockLogin(1L, "admin")) {
            when(jdbcTemplate.queryForList(contains("caps_corp_cust_basic_info"), eq("C404")))
                    .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("caps_indv_cust_basic_info"), eq("C404")))
                    .thenReturn(List.of());

            ServiceException e = assertThrows(ServiceException.class, () -> queryService.orgAchievement("C404"));
            assertEquals(ErrorCode.NOT_FOUND.getCode(), e.getCode());
        }
    }

    @Test
    void orgAchievement_noDwSnapshot_returnsNullSnapshotFields() {
        try (MockedStatic<StpUtil> ignored = mockLogin(1L, "admin")) {
            when(jdbcTemplate.queryForList(contains("caps_corp_cust_basic_info"), eq("C001")))
                    .thenReturn(List.of(Map.of("openact_org_no", "ORG01", "openact_org_nm", "城东支行")));
            when(jdbcTemplate.queryForList(contains("dw_org_performance_snapshot"), eq("ORG01")))
                    .thenReturn(List.of());
            when(jdbcTemplate.queryForMap(anyString(), any(Object[].class)))
                    .thenReturn(Map.of("planCount", 0L, "avgAchievementRatio", BigDecimal.ZERO));

            Map<String, Object> result = queryService.orgAchievement("C001");

            assertEquals("ORG01", result.get("orgCode"));
            assertNull(result.get("statMonth"));
            assertNull(result.get("completionRate"));
            assertNotNull(result.get("orgPlanStats"));
        }
    }
}
