package com.ccr.commitment.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 承诺指标(ccr_commitment_metric)——基线/目标/单位/计算版本(§11.3)
 * 目标类型: INCREMENT/TARGET_BALANCE/CUMULATIVE
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_commitment_metric")
public class CcrCommitmentMetric extends BaseEntity {

    /** 承诺计划主键 */
    private Long planId;

    /** 稳定指标编码 */
    private String metricCode;

    /** 指标名称 */
    private String metricName;

    /** INCREMENT / TARGET_BALANCE / CUMULATIVE */
    private String targetType;

    /** 基线值(万元) */
    private BigDecimal baselineValue;

    /** 目标值(万元);INCREMENT 类型下存"承诺新增值"(§11.3 公式分母) */
    private BigDecimal targetValue;

    /** 单位 */
    private String unit;

    /** 计算版本 */
    private String calcVersion;

    /** 指标范围 */
    private String metricScope;
}
