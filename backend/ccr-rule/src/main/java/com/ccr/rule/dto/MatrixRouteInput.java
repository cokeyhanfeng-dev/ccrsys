package com.ccr.rule.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PRD V2 §7.2 权限矩阵路由输入(逐担保类型, D18a)
 * 冻结语义(§8.4):提交时在途申请冻结 lprVersionId + asOfDate,路由重算沿用冻结版本
 */
@Data
public class MatrixRouteInput {

    /** 金额定档基准:集团综合授信批复总额度(§B18 默认) */
    public static final String AMOUNT_BASIS_GROUP_TOTAL_CREDIT = "GROUP_TOTAL_CREDIT";

    /** 金额定档基准:本笔申请金额 */
    public static final String AMOUNT_BASIS_APPLY_AMOUNT = "APPLY_AMOUNT";

    /** 业务大类:LOAN_PUBLIC/LOAN_PERSONAL/DEPOSIT/MARGIN */
    private String businessBigType;

    /** NEW新增授信/EXISTING存量授信 */
    private String newOrExisting;

    /** SOE国企/NON_SOE非国企/PERSONAL个人 */
    private String customerType;

    /** 产品编码(存款/保证金区分产品:协定/通知/银票/信用证等;贷款可空) */
    private String productCode;

    /** 申请金额(万元) */
    private BigDecimal amount;

    /** 金额定档基准:GROUP_TOTAL_CREDIT(默认)/APPLY_AMOUNT */
    private String amountBasis = AMOUNT_BASIS_GROUP_TOTAL_CREDIT;

    /** 集团综合授信批复总额度(万元);amountBasis=GROUP_TOTAL_CREDIT 且非空时以此定金额档(§B18) */
    private BigDecimal groupCreditTotal;

    /** 期限数值 */
    private Integer termValue;

    /** 期限单位(日/月/年;DAY/MONTH/YEAR) */
    private String termUnit;

    /** 担保主类型 */
    private String guaranteeType;

    /** 申请利率(%) */
    private BigDecimal requestedRate;

    /** 原执行利率(%)(存量降幅用) */
    private BigDecimal originalRate;

    /** 冻结的 LPR 版本主键(§8.4;空=取当前生效版本) */
    private Long lprVersionId;

    /** 冻结的矩阵生效日期(§8.4;空=按当前时间取生效行) */
    private LocalDateTime asOfDate;
}
