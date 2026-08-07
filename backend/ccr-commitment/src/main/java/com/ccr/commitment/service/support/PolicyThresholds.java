package com.ccr.commitment.service.support;

import com.ccr.commitment.domain.CcrTrackingThreshold;

import java.math.BigDecimal;
import java.util.List;

/**
 * 跟踪策略阈值(§11.5)——从冻结策略版本解析,替换硬编码 0.8/1.0
 * 阈值缺省: 达成线 1.0、风险线 0.8、临近到期 7 天、数据容忍 7 天
 */
public class PolicyThresholds {

    /** 默认达成线 */
    public static final BigDecimal DEFAULT_ACHIEVE_LINE = BigDecimal.ONE;
    /** 默认风险线 */
    public static final BigDecimal DEFAULT_AT_RISK_LINE = new BigDecimal("0.8");
    /** 默认临近到期天数 */
    public static final int DEFAULT_NEAR_EXPIRY_DAYS = 7;
    /** 默认数据容忍天数 */
    public static final int DEFAULT_TOLERANCE_DAYS = 7;

    private final BigDecimal achieveLine;
    private final BigDecimal atRiskLine;
    private final int nearExpiryDays;
    private final int toleranceDays;

    private PolicyThresholds(BigDecimal achieveLine, BigDecimal atRiskLine, int nearExpiryDays, int toleranceDays) {
        this.achieveLine = achieveLine;
        this.atRiskLine = atRiskLine;
        this.nearExpiryDays = nearExpiryDays;
        this.toleranceDays = toleranceDays;
    }

    /**
     * 从策略版本阈值行解析:
     * ACHIEVEMENT_RATE + risk_level=AT_RISK(操作符</<=) → 风险线(取最大);
     * ACHIEVEMENT_RATE + risk_level=NORMAL(操作符>/>=) → 达成线(取最小);
     * NEAR_EXPIRY → 临近到期天数;容忍天数取策略版本 data_tolerance_days
     */
    public static PolicyThresholds from(List<CcrTrackingThreshold> thresholds, Integer dataToleranceDays) {
        BigDecimal achieve = null;
        BigDecimal atRisk = null;
        Integer nearExpiry = null;
        if (thresholds != null) {
            for (CcrTrackingThreshold t : thresholds) {
                if (t.getThresholdValue() == null) {
                    continue;
                }
                if ("ACHIEVEMENT_RATE".equals(t.getThresholdType())) {
                    if ("AT_RISK".equals(t.getRiskLevel())
                            && (atRisk == null || t.getThresholdValue().compareTo(atRisk) > 0)) {
                        atRisk = t.getThresholdValue();
                    }
                    if ("NORMAL".equals(t.getRiskLevel())
                            && (achieve == null || t.getThresholdValue().compareTo(achieve) < 0)) {
                        achieve = t.getThresholdValue();
                    }
                } else if ("NEAR_EXPIRY".equals(t.getThresholdType())) {
                    nearExpiry = t.getThresholdValue().intValue();
                }
            }
        }
        return new PolicyThresholds(
                achieve == null ? DEFAULT_ACHIEVE_LINE : achieve,
                atRisk == null ? DEFAULT_AT_RISK_LINE : atRisk,
                nearExpiry == null ? DEFAULT_NEAR_EXPIRY_DAYS : nearExpiry,
                dataToleranceDays == null ? DEFAULT_TOLERANCE_DAYS : dataToleranceDays);
    }

    /** 兜底策略(未冻结/未匹配到策略时) */
    public static PolicyThresholds defaults() {
        return from(null, null);
    }

    public BigDecimal achieveLine() {
        return achieveLine;
    }

    public BigDecimal atRiskLine() {
        return atRiskLine;
    }

    public int nearExpiryDays() {
        return nearExpiryDays;
    }

    public int toleranceDays() {
        return toleranceDays;
    }

    /**
     * 单指标结果判定
     *
     * @param ratio   达成率(null=无数据)
     * @param expired 数据日期是否已到/超过到期日
     * @param dataStale 数据是否超容忍天数
     */
    public String resolveStatus(BigDecimal ratio, boolean expired, boolean dataStale) {
        if (ratio == null || dataStale) {
            return "DATA_PENDING";
        }
        if (ratio.compareTo(achieveLine) >= 0) {
            return "ACHIEVED";
        }
        if (expired) {
            return "EXPIRED_UNMET";
        }
        if (ratio.compareTo(atRiskLine) < 0) {
            return "AT_RISK";
        }
        return "ON_TRACK";
    }

    /** 风险等级判定 */
    public String resolveRisk(BigDecimal ratio) {
        if (ratio == null) {
            return "NORMAL";
        }
        if (ratio.compareTo(atRiskLine) < 0) {
            return "AT_RISK";
        }
        if (ratio.compareTo(achieveLine) >= 0) {
            return "NORMAL";
        }
        return "WATCH";
    }
}
