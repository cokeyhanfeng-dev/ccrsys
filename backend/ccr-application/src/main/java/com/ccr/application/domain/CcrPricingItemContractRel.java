package com.ccr.application.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 分项与贷款合同关系(ccr_pricing_item_contract_rel)
 * 一份现有或拟签合同在同一时间只允许一个有效定价分项
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_pricing_item_contract_rel")
public class CcrPricingItemContractRel extends BaseEntity {

    /** 申请主键 */
    private Long applicationId;

    /** 定价分项主键 */
    private Long pricingItemId;

    /** 合同业务标识(现有合同号或拟签合同标识) */
    private String contractBusinessKey;

    /** 回填的正式合同号 */
    private String loanContractNo;

    /** 冻结合同快照记录id */
    private Long contractSnapshotId;

    /** 是否拟签合同 Y/N */
    private String plannedContractFlag;
}
