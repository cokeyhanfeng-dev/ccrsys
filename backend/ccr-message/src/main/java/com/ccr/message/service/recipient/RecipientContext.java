package com.ccr.message.service.recipient;

import lombok.Data;

/**
 * 接收人解析上下文(§11.6)——由触发方(commitment/resolution)提供
 */
@Data
public class RecipientContext {

    /** 承诺计划主键(可为空,如 resolution 核验异常直接触发) */
    private Long planId;

    /** 跟踪评估主键 */
    private Long evaluationId;

    /** 计划归属机构 */
    private Long orgId;

    /** 客户号 */
    private String customerNo;

    /** 集团号 */
    private String groupNo;

    /** 成员客户号(成员级承诺) */
    private String memberCustomerNo;

    /** 来源决议主键 */
    private Long resolutionId;
}
