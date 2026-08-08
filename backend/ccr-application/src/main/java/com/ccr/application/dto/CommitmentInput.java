package com.ccr.application.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 拟达成贡献度承诺录入(§7.1;审批通过后由承诺模块生成正式承诺计划)
 */
@Data
public class CommitmentInput {

    /** 关联定价分项编号(可空,创建时按编号解析为分项主键) */
    private String pricingItemNo;

    /** 贡献度指标编码 */
    private String metricCode;

    /** 达成率算法类型(与承诺模块 §11.3 校验一致):INCREMENT/TARGET_BALANCE/CUMULATIVE */
    private String targetType;

    /** 基线值(万元) */
    private BigDecimal baselineValue;

    /** 拟达成目标值(万元) */
    private BigDecimal targetValue;

    /** 计量单位 */
    private String unit;

    /** 指标范围:PUBLIC/PRIVATE_SELF/RELATED/GROUP/GROUP_MEMBER */
    private String metricScope;

    /** 集团成员客户号(集团场景) */
    private String memberCustomerNo;
}
