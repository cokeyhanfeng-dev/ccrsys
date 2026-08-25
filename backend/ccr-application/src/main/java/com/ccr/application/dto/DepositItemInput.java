package com.ccr.application.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 存款定价分项结构化录入(DEPOSIT 申请按存款字段生成定价分项,不再依赖担保列表)
 */
@Data
public class DepositItemInput {

    /** 集团成员客户号(集团场景必填) */
    private String memberCustomerNo;

    /** 存款产品编码(协定/通知/定期等) */
    private String productCode;

    /** 期限数值(活期可空) */
    private Integer termValue;

    /** 期限单位:DAY/MONTH/YEAR */
    private String termUnit;

    /** 申请金额(万元) */
    private BigDecimal amount;

    /** 币种 */
    private String currency;

    /** 申请利率(%) */
    private BigDecimal requestedRate;

    /** 原执行利率(%)(存量存款调价) */
    private BigDecimal originalRate;

    /** 测算利率(%)(业务人员手工录入,必填) */
    private BigDecimal calculatedRate;

    /** 存款账号(明文,拟开户可空;存量调价直接填写或选择数仓账户明文账号) */
    private String depositAccountNo;

    /** 是否拟开户方案 Y/N */
    private String plannedAccountFlag;
}
