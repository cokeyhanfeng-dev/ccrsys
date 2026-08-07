package com.ccr.rule.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 产品标准与业务硬边界(ccr_product_rate_limit)
 * 贷款:全行不可低于硬边界;存款:全行不可高于硬边界(§8.2)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_product_rate_limit")
public class CcrProductRateLimit extends BaseEntity {

    /** 产品编码 */
    private String productCode;

    /** 产品名称 */
    private String productName;

    /** LOAN / DEPOSIT */
    private String businessType;

    /** 业务硬边界利率(%) */
    private BigDecimal hardBoundaryRate;

    /** LOWER_BETTER / HIGHER_BETTER */
    private String rateDirection;

    /** 生效时间 */
    private LocalDateTime effectiveFrom;

    /** 失效时间 */
    private LocalDateTime effectiveTo;

    /** 发布人(双人复核) */
    private Long publishBy;

    /** 复核人 */
    private Long reviewBy;

    /** 发布时间 */
    private LocalDateTime publishTime;
}
