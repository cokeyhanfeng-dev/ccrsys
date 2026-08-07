package com.ccr.application.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 集团申请涉及成员(ccr_application_member)——仅集团场景
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_application_member")
public class CcrApplicationMember extends BaseEntity {

    /** 申请主键 */
    private Long applicationId;

    /** 本次涉及成员客户号 */
    private String memberCustomerNo;

    /** 数仓成员额度来源主键 */
    private String memberLimitRef;

    /** 提交时成员额度快照值 */
    private BigDecimal memberLimitAmount;

    /** 本次申请涉及金额 */
    private BigDecimal requestAmount;

    /** ISO 币种编码 */
    private String currency;

    /** 冻结成员角色(核心/一般) */
    private String memberRole;
}
