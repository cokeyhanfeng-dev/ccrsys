package com.ccr.application.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.baomidou.mybatisplus.annotation.TableField;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 担保组合(ccr_guarantee_package)——一个定价分项对应一个冻结担保组合版本,提交后不可修改
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ccr_guarantee_package", autoResultMap = true)
public class CcrGuaranteePackage extends BaseEntity {

    /** 担保组合编号 */
    private String packageNo;

    /** 定价分项(唯一) */
    private Long pricingItemId;

    /** 担保组合版本 */
    private Integer packageVersion;

    /** 担保主类型 */
    private String mainGuaranteeType;

    /** 扩展属性 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> extJson;
}
