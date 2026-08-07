package com.ccr.commitment.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 跟踪阈值(ccr_tracking_threshold)——时间进度/达成率/连续下降/临近到期(§11.5)
 * threshold_type: TIME_PROGRESS/ACHIEVEMENT_RATE/CONSECUTIVE_DECLINE/NEAR_EXPIRY
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_tracking_threshold")
public class CcrTrackingThreshold extends BaseEntity {

    /** 策略版本主键 */
    private Long policyVersionId;

    /** 阈值类型 */
    private String thresholdType;

    /** 阈值数值(比率;NEAR_EXPIRY 为天数) */
    private BigDecimal thresholdValue;

    /** 命中后预警等级 NORMAL/WATCH/AT_RISK */
    private String riskLevel;

    /** 比较符 > >= < <= */
    private String compareOperator;
}
