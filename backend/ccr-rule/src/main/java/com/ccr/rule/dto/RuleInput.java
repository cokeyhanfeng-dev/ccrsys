package com.ccr.rule.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 路由计算输入维度(设计文档 V1.0 §8.1 规则输入)
 */
@Data
public class RuleInput {

    /** LOAN / DEPOSIT */
    private String businessType;

    /** 产品编码 */
    private String productCode;

    /** NEW / EXISTING */
    private String newOrExisting;

    /** 对公/个人 */
    private String customerType;

    /** 国企属性 Y/N */
    private String stateOwnedFlag;

    /** 集团授信总额(万元);金额档次默认按集团综合授信批复总额度(§B18) */
    private BigDecimal groupCreditTotal;

    /** 金额定档基准:GROUP_TOTAL_CREDIT(默认,按集团综合授信批复总额度)/APPLY_AMOUNT(按本笔申请金额) */
    private String amountBasis = "GROUP_TOTAL_CREDIT";

    /** 成员申请金额(万元) */
    private BigDecimal applyAmount;

    /** 期限数值 */
    private Integer termValue;

    /** 期限单位(日/月/年) */
    private String termUnit;

    /** 担保主类型 */
    private String guaranteeType;

    /** LPR 期限(1Y/5Y+) */
    private String lprTerm;

    /** 机构编码 */
    private String orgCode;

    /** 币种 */
    private String currency;

    /** 申请利率(%) */
    private BigDecimal requestedRate;
}
