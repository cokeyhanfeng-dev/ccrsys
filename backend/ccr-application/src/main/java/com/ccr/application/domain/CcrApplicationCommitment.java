package com.ccr.application.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 拟达成贡献度承诺(ccr_application_commitment)——申请创建/提交时随单录入;
 * 审批通过后由承诺模块读取生成正式承诺计划
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_application_commitment")
public class CcrApplicationCommitment extends BaseEntity {

    /** 申请主键 */
    private Long applicationId;

    /** 关联定价分项(可空) */
    private Long pricingItemId;

    /** 贡献度指标编码 */
    private String metricCode;

    /** 达成率算法类型(与承诺模块 §11.3 校验一致):INCREMENT/TARGET_BALANCE/CUMULATIVE */
    private String targetType;

    /** 基线值(万元) */
    private BigDecimal baselineValue;

    /** 拟达成目标值(万元) */
    private BigDecimal targetValue;

    /** 计量单位 */
    private String unit;

    /** 指标范围:PUBLIC/PRIVATE_SELF/RELATED/GROUP/GROUP_MEMBER */
    private String metricScope;

    /** 集团成员客户号(集团场景) */
    private String memberCustomerNo;

    /** 承诺类型"其它"(§6.4)手工目标描述(金额或文本);该类型下 targetValue 可空 */
    private String commitmentDesc;
}
