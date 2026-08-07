package com.ccr.application.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 集团申请成员录入(前端逐成员传金额/币种/角色)
 */
@Data
public class MemberInput {

    /** 成员客户号 */
    private String memberCustomerNo;

    /** 本次申请涉及金额(万元) */
    private BigDecimal requestAmount;

    /** ISO 币种编码 */
    private String currency;

    /** 成员角色(CORE/GENERAL;空则按数仓成员快照回填) */
    private String memberRole;
}
