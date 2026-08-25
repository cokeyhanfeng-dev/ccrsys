package com.ccr.application.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 申请关联人(客户经理实际录入,§12.4④;审批详情按录入内容展示)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_application_related_person")
public class CcrApplicationRelatedPerson extends BaseEntity {

    /** 申请主键 */
    private Long applicationId;

    /** 姓名/名称 */
    private String personName;

    /** 证件号码 */
    private String certNo;

    /** 证件类型(USCC对公/ID_CARD对私;增量021,后端兜底反查关联人客户号用) */
    private String certType;

    /** 关系类型(配偶/直系亲属/担保人/实际控制人等) */
    private String relationType;

    /** 行内客户号(自动匹配或手工补录) */
    private String relatedCustomerNo;
}
