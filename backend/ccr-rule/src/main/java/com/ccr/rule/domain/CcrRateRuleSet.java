package com.ccr.rule.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 利率规则集版本(ccr_rate_rule_set)
 * 状态: DRAFT/REVIEW/EFFECTIVE/INVALID
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_rate_rule_set")
public class CcrRateRuleSet extends BaseEntity {

    /** 规则集编码(唯一) */
    private String setCode;

    /** 规则集名称 */
    private String setName;

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

    /** 备注 */
    private String remark;
}
