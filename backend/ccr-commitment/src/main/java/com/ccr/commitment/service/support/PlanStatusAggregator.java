package com.ccr.commitment.service.support;

import java.util.List;

/**
 * 计划状态聚合(§11.1):多指标计划状态由全部指标最新结果聚合,不再被单指标覆盖
 * 规则: 任一 AT_RISK→AT_RISK;全部 ACHIEVED→ACHIEVED;到期有未达成→EXPIRED_UNMET;
 * 有数据缺失→DATA_PENDING;否则 TRACKING
 */
public final class PlanStatusAggregator {

    private PlanStatusAggregator() {
    }

    /**
     * @param metricStatuses 各指标最新评估结果(ON_TRACK/AT_RISK/ACHIEVED/EXPIRED_UNMET/DATA_PENDING)
     * @param expired        计划是否已到期
     * @param evaluated      是否已发生过评估(false=尚未评估,保持 PENDING)
     */
    public static String aggregate(List<String> metricStatuses, boolean expired, boolean evaluated) {
        if (!evaluated) {
            return "PENDING";
        }
        if (metricStatuses == null || metricStatuses.isEmpty()) {
            return "TRACKING";
        }
        boolean anyAtRisk = metricStatuses.stream().anyMatch("AT_RISK"::equals);
        if (anyAtRisk) {
            return "AT_RISK";
        }
        boolean allAchieved = metricStatuses.stream().allMatch("ACHIEVED"::equals);
        if (allAchieved) {
            return "ACHIEVED";
        }
        boolean anyExpiredUnmet = metricStatuses.stream().anyMatch("EXPIRED_UNMET"::equals);
        if (expired || anyExpiredUnmet) {
            return "EXPIRED_UNMET";
        }
        boolean anyDataPending = metricStatuses.stream().anyMatch("DATA_PENDING"::equals);
        if (anyDataPending) {
            return "DATA_PENDING";
        }
        return "TRACKING";
    }
}
