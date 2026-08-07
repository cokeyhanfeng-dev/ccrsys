package com.ccr.commitment.service.support;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 计划状态聚合 + 阈值判定单测(§11.1/§11.5)
 */
class PlanStatusAggregatorTest {

    @Test
    void aggregateRules() {
        // 任一 AT_RISK → AT_RISK
        assertEquals("AT_RISK", PlanStatusAggregator.aggregate(
                List.of("ACHIEVED", "AT_RISK"), false, true));
        // 全部 ACHIEVED → ACHIEVED
        assertEquals("ACHIEVED", PlanStatusAggregator.aggregate(
                List.of("ACHIEVED", "ACHIEVED"), false, true));
        // 到期有未达成 → EXPIRED_UNMET
        assertEquals("EXPIRED_UNMET", PlanStatusAggregator.aggregate(
                List.of("ON_TRACK"), true, true));
        assertEquals("EXPIRED_UNMET", PlanStatusAggregator.aggregate(
                List.of("EXPIRED_UNMET", "ON_TRACK"), false, true));
        // 数据缺失 → DATA_PENDING
        assertEquals("DATA_PENDING", PlanStatusAggregator.aggregate(
                List.of("ON_TRACK", "DATA_PENDING"), false, true));
        // 正常跟踪
        assertEquals("TRACKING", PlanStatusAggregator.aggregate(
                List.of("ON_TRACK", "ON_TRACK"), false, true));
        // 未评估保持 PENDING
        assertEquals("PENDING", PlanStatusAggregator.aggregate(List.of(), false, false));
    }

    @Test
    void thresholdsJudgement() {
        PolicyThresholds pts = PolicyThresholds.defaults();
        assertEquals("ACHIEVED", pts.resolveStatus(new BigDecimal("1.0"), false, false));
        assertEquals("AT_RISK", pts.resolveStatus(new BigDecimal("0.79"), false, false));
        assertEquals("ON_TRACK", pts.resolveStatus(new BigDecimal("0.85"), false, false));
        assertEquals("EXPIRED_UNMET", pts.resolveStatus(new BigDecimal("0.9"), true, false));
        // 数据超容忍不得判未履约
        assertEquals("DATA_PENDING", pts.resolveStatus(new BigDecimal("0.1"), true, true));
        assertEquals("DATA_PENDING", pts.resolveStatus(null, false, false));
        assertEquals("AT_RISK", pts.resolveRisk(new BigDecimal("0.5")));
        assertEquals("WATCH", pts.resolveRisk(new BigDecimal("0.9")));
        assertEquals("NORMAL", pts.resolveRisk(new BigDecimal("1.2")));
    }
}
