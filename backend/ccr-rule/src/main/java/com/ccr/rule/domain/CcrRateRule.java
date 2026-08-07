package com.ccr.rule.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 具体利率匹配规则(ccr_rate_rule)——唯一路由
 * 空条件=通配;优先匹配;互斥条件组内只能命中一条
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_rate_rule")
public class CcrRateRule extends BaseEntity {

    /** 规则集主键 */
    private Long setId;

    /** 规则编码(集内唯一) */
    private String ruleCode;

    /** 规则名称 */
    private String ruleName;

    /** LOAN / DEPOSIT */
    private String businessType;

    /** 产品编码(空=通配) */
    private String productCode;

    /** 对公/个人(空=通配) */
    private String customerType;

    /** NEW / EXISTING(空=通配) */
    private String newOrExisting;

    /** 国企属性 Y/N(空=通配) */
    private String stateOwnedFlag;

    /** 集团授信总额下限(万元) */
    private BigDecimal groupCreditMin;

    /** 集团授信总额上限 */
    private BigDecimal groupCreditMax;

    /** 申请金额下限 */
    private BigDecimal amountMin;

    /** 申请金额上限 */
    private BigDecimal amountMax;

    /** 期限下限 */
    private Integer termMin;

    /** 期限上限 */
    private Integer termMax;

    /** 期限单位 */
    private String termUnit;

    /** 担保主类型(空=通配) */
    private String guaranteeType;

    /** LPR 期限(1Y/5Y+) */
    private String lprTerm;

    /** 机构(空=通配) */
    private String orgCode;

    /** 币种 */
    private String currency;

    /** 起始节点 */
    private String startNodeCode;

    /** LOWER_BETTER / HIGHER_BETTER */
    private String rateDirection;

    /** 匹配优先级(低值优先) */
    private Integer priority;

    /** 互斥条件组 */
    private String mutexGroup;

    /** 规则说明 */
    private String description;
}
