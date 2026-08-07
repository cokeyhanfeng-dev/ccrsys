package com.ccr.approval.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 数仓借据快照(dw_loan_note_snapshot)——历史档案借据区块数据源(§14.4)
 * 数仓落地表,技术头为 etl_md5 + data_dt(参照 db/02 既有 dw_ 表),业务代码只读;
 * 与 ccr-resolution 同名字段保持一致(第二级核验同款口径)
 */
@Data
@TableName("dw_loan_note_snapshot")
public class DwLoanNoteSnapshot implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 数仓技术主键 */
    @TableId(value = "etl_md5", type = IdType.AUTO)
    private Long etlMd5;

    /** 数据日期(DATA_DT,数仓批次标识) */
    private LocalDate dataDt;

    /** 借据号 */
    private String loanNoteNo;

    /** 贷款合同号 */
    private String contractNo;

    /** 额度编号 */
    private String trancheNo;

    /** 借款客户号 */
    private String borrowerCustomerNo;

    /** 借据金额 */
    private BigDecimal loanAmount;

    /** 借据余额 */
    private BigDecimal loanBalance;

    /** 币种 */
    private String currency;

    /** 借据执行利率(%) */
    private BigDecimal executionRate;

    /** 利率类型 */
    private String rateType;

    /** LPR 期限 */
    private String lprTerm;

    /** 借据起息日 */
    private LocalDate startDate;

    /** 借据到期日 */
    private LocalDate maturityDate;

    /** 借据状态(有效借据为 ACTIVE) */
    private String noteStatus;
}
