package com.ccr.application.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 他行融资概要快照(申请随单可编辑,§2026-08-25)
 * 数仓 dw_credit_financing_summary 带出默认值,客户经理申请页可调;
 * 提交时与融资明细(ccr_application_other_loan)加总做对应校验,审批详情随申请展示。
 * reportDate 为征信报告日期(数仓 dw_credit_report_snapshot 带出,§2026-08-26)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_application_credit_summary")
public class CcrApplicationCreditSummary extends BaseEntity {

    /** 申请主键 */
    private Long applicationId;

    /** 授信机构数 */
    private Integer lenderCount;

    /** 他行授信总额(万元) */
    private BigDecimal creditAmountTotal;

    /** 已用额度合计(万元) */
    private BigDecimal usedAmountTotal;

    /** 未结清笔数 */
    private Integer loanAccountCount;

    /** 逾期账户数 */
    private Integer overdueAccountCount;

    /** 逾期余额(万元) */
    private BigDecimal overdueBalance;

    /** 不良贷款余额(万元) */
    private BigDecimal nplBalance;

    /** 关注类余额(万元) */
    private BigDecimal specialMentionBalance;

    /** 对外担保余额(万元) */
    private BigDecimal externalGuaranteeBalance;

    /** 征信报告日期(数仓 dw_credit_report_snapshot 带出,只读展示) */
    private LocalDate reportDate;
}
