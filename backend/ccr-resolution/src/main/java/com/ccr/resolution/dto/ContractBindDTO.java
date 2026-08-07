package com.ccr.resolution.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 合同回填入参(§7.7 回填校验七项:客户、产品、金额、期限、担保主类型、决议有效期、最终利率)
 */
@Data
public class ContractBindDTO {

    /** 回填正式合同号(必填) */
    private String loanContractNo;

    /** 补充协议编号(存量调息场景必填) */
    private String supplementAgreementNo;

    /** 合同执行利率(%,必填) */
    private BigDecimal executionRate;

    /** 合同客户号 */
    private String customerNo;

    /** 合同产品编码 */
    private String productCode;

    /** 合同金额(万元) */
    private BigDecimal contractAmount;

    /** 合同期限数值 */
    private Integer termValue;

    /** 合同期限单位(日/月/年) */
    private String termUnit;

    /** 合同担保主类型 */
    private String guaranteeType;

    /** 合同/补充协议签署日期(须落在决议有效期内) */
    private LocalDate signDate;
}
