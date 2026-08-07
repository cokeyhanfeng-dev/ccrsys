package com.ccr.application.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 担保措施(ccr_guarantee_measure)
 * 类型: CREDIT/GUARANTEE/MORTGAGE/PLEDGE/BILL_MARGIN/CREDIT_MARGIN/CERTIFICATE_DEPOSIT
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ccr_guarantee_measure", autoResultMap = true)
public class CcrGuaranteeMeasure extends BaseEntity {

    /** 担保组合主键 */
    private Long packageId;

    /** 担保措施编号(唯一) */
    private String measureNo;

    /** 措施类型 */
    private String measureType;

    /** 担保人客户号(保证) */
    private String guarantorCustomerNo;

    /** 抵质押物编号 */
    private String collateralNo;

    /** 担保金额(万元) */
    private BigDecimal guaranteeAmount;

    /** 币种 */
    private String currency;

    /** 扩展属性 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> extJson;
}
