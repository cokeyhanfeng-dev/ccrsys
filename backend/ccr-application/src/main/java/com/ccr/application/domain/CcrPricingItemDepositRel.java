package com.ccr.application.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 分项与存款账户关系(ccr_pricing_item_deposit_rel)
 * 有效记录中实际存款账号只归属一个有效分项;拟开户时账号可空
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_pricing_item_deposit_rel")
public class CcrPricingItemDepositRel extends BaseEntity {

    /** 申请主键 */
    private Long applicationId;

    /** 定价分项主键 */
    private Long pricingItemId;

    /** 存款账号密文(拟开户可空) */
    private String depositAccountNoCipher;

    /** 存款账号查询哈希 */
    private String depositAccountHash;

    /** 冻结账户快照记录id */
    private Long accountSnapshotId;

    /** 是否拟开户方案 Y/N */
    private String plannedAccountFlag;
}
