package com.ccr.application.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 原申请与重提关系(ccr_application_relation)——重提/替换/分项沿用
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_application_relation")
public class CcrApplicationRelation extends BaseEntity {

    /** 原申请主键 */
    private Long sourceApplicationId;

    /** 重提申请主键 */
    private Long targetApplicationId;

    /** REAPPLY重提 / REPLACE替换 */
    private String relationType;

    /** 原定价分项主键 */
    private Long sourcePricingItemId;

    /** 重提定价分项主键 */
    private Long targetPricingItemId;

    /** 是否沿用原决议 Y/N */
    private String inheritFlag;

    /** 备注 */
    private String remark;
}
