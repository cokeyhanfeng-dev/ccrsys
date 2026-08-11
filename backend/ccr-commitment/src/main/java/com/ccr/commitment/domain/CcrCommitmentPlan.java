package com.ccr.commitment.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.util.Map;

/**
 * 承诺跟踪计划(ccr_commitment_plan)——审批通过后生成(§11.1)
 * 状态: PENDING/TRACKING/AT_RISK/ACHIEVED/EXPIRED_UNMET/DATA_PENDING/TERMINATED/SUPERSEDED
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ccr_commitment_plan", autoResultMap = true)
public class CcrCommitmentPlan extends BaseEntity {

    /** 承诺计划编号(唯一) */
    private String planNo;

    /** 来源决议主键 */
    private Long resolutionId;

    /** 所属申请(承诺按申请级聚合) */
    private Long applicationId;

    /** INDIVIDUAL / CORPORATE_SINGLE / MEMBER / GROUP */
    private String scopeType;

    /** 个人或企业客户号 */
    private String customerNo;

    /** 集团号 */
    private String groupNo;

    /** 成员客户号(成员级承诺) */
    private String memberCustomerNo;

    /** FIXED_ALLOCATION固定分配 / GROUP_SHARED集团共享 */
    private String allocationMode;

    /** 开始日期 */
    private LocalDate startDate;

    /** 到期日期 */
    private LocalDate endDate;

    /** 冻结跟踪策略版本 */
    private Long policyVersionId;

    /** 冻结成员集合快照 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> memberFrozenJson;
}
