package com.ccr.application.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 人工补录他行融资(申请随单录入,§7.1 步骤6)
 * 与数仓权威征信快照分离存储(input_mode 标记人工/Excel),审批详情随申请展示
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_application_other_loan")
public class CcrApplicationOtherLoan extends BaseEntity {

    /** 申请主键 */
    private Long applicationId;

    /** 他行机构名称 */
    private String lenderName;

    /** 授信额(万元) */
    private BigDecimal creditAmount;

    /** 已用额(万元) */
    private BigDecimal usedAmount;

    /** 余额(万元) */
    private BigDecimal balanceAmount;

    /** 年化利率% */
    private BigDecimal annualRate;

    /** 录入方式:MANUAL人工/EXCEL导入 */
    private String inputMode;
}
