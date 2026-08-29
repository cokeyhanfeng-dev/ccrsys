package com.ccr.resolution.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 审批决议(ccr_resolution)——决议是内部定价审批结论,不是贷款合同(§B20)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_resolution")
public class CcrResolution extends BaseEntity {

    /** 决议编号(唯一) */
    private String resolutionNo;

    /** 申请主键(整单交付改造 2026-08-29:决议按申请维度生成,一申请一份) */
    private Long applicationId;

    /** 定价分项主键(历史决议兼容;整单化新决议为空) */
    private Long pricingItemId;

    /** LOAN_CONTRACT / DEPOSIT_ACCOUNT */
    private String pricingCarrierType;

    /** 执行载体业务标识 */
    private String pricingCarrierBusinessKey;

    /** 最终决议利率(%) */
    private BigDecimal finalRate;

    /** 决议生效日 */
    private LocalDate effectiveFrom;

    /** 决议失效日 */
    private LocalDate effectiveTo;

    /** VOTE_APPROVED / PRESIDENT_APPROVED */
    private String decisionSource;

    /** 决议签发时间 */
    private LocalDateTime issueTime;
}
