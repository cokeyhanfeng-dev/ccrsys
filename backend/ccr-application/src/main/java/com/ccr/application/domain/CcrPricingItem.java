package com.ccr.application.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 利率定价分项(ccr_pricing_item)——审批原子单位
 * 状态: DRAFT/ROUTING/LEVEL_APPROVAL/VOTING/PRESIDENT_DECISION/APPROVED/REJECTED/VETOED/SUPERSEDED/CLOSED
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_pricing_item")
public class CcrPricingItem extends BaseEntity {

    /** 所属申请 */
    private Long applicationId;

    /** 定价分项编号(唯一) */
    private String pricingItemNo;

    /** 实际定价客户号(集团场景为成员) */
    private String pricingCustomerNo;

    /** 集团成员客户号(集团场景必填) */
    private String memberCustomerNo;

    /** LOAN_CONTRACT / DEPOSIT_ACCOUNT */
    private String pricingCarrierType;

    /** 用信或担保分项来源主键 */
    private String creditTrancheRef;

    /** 产品编码 */
    private String productCode;

    /** 冻结担保组合 */
    private Long guaranteePackageId;

    /** 期限数值 */
    private Integer termValue;

    /** 日/月/年 */
    private String termUnit;

    /** 分项定价金额(万元) */
    private BigDecimal pricingAmount;

    /** 币种 */
    private String currency;

    /** 原执行利率(新增业务可为空) */
    private BigDecimal originalRate;

    /** 客户经理申请利率 */
    private BigDecimal requestedRate;

    /** 当前审批利率(随调价更新) */
    private BigDecimal currentApprovalRate;

    /** 最终决议利率 */
    private BigDecimal finalRate;

    /** LOWER_BETTER / HIGHER_BETTER */
    private String rateDirection;

    /** 计算得到路由编码 */
    private String routeCode;

    /** 工作流实例标识 */
    private String flowInstanceId;

    /** 当前节点编码 */
    private String currentNodeCode;

    /** 最终否决或一票否决原因 */
    private String finalReason;

    /** 关联重提来源分项(§7.6) */
    private Long sourcePricingItemId;

    /** 是否沿用原决议 Y/N(D18b:已批准分项连同最终利率与快照保留,不重新审批) */
    private String inheritFlag;
}
