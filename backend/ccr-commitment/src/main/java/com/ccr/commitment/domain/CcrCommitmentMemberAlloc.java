package com.ccr.commitment.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 集团承诺成员分配(ccr_commitment_member_alloc)——固定分配合计=集团目标(§11.2)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_commitment_member_alloc")
public class CcrCommitmentMemberAlloc extends BaseEntity {

    /** 承诺计划主键 */
    private Long planId;

    /** 承诺指标主键 */
    private Long metricId;

    /** 成员客户号 */
    private String memberCustomerNo;

    /** 成员目标(万元) */
    private BigDecimal allocatedTarget;

    /** 成员基线 */
    private BigDecimal allocatedBaseline;

    /** 请求入参用:关联的指标编码(非表字段,落库时换算为 metric_id) */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String metricCode;
}
