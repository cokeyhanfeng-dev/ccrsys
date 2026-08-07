package com.ccr.commitment.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 跟踪策略主表(ccr_tracking_policy)——版本化(§11.5)
 * 匹配优先级: 指标+业务+机构 > 指标+业务 > 指标默认 > 全行默认(metric_code='*')
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_tracking_policy")
public class CcrTrackingPolicy extends BaseEntity {

    /** 策略编号(唯一) */
    private String policyNo;

    /** 策略名称 */
    private String policyName;

    /** 指标编码(*=全行默认) */
    private String metricCode;

    /** 业务类型(空=不限) */
    private String businessType;

    /** 机构编码(空=通用) */
    private String orgCode;

    /** 优先级(同维度内数值大者优先) */
    private Integer priority;
}
