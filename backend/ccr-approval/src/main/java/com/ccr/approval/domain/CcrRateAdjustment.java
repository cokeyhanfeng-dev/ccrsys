package com.ccr.approval.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 节点调价记录(ccr_rate_adjustment)——保存前值/后值/权限边界/操作人/渠道(§9.2/§15)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_rate_adjustment")
public class CcrRateAdjustment extends BaseEntity {

    /** 定价分项主键 */
    private Long pricingItemId;

    /** 审批节点编码 */
    private String nodeCode;

    /** 调整前利率 */
    private BigDecimal beforeRate;

    /** 调整后利率 */
    private BigDecimal afterRate;

    /** 本节点权限下界 */
    private BigDecimal boundaryMinRate;

    /** 本节点权限上界 */
    private BigDecimal boundaryMaxRate;

    /** 调整理由 */
    private String adjustReason;

    /** 操作人 */
    private Long operatorId;

    /** PC / MOBILE */
    private String operationChannel;

    /** 设备标识 */
    private String deviceId;

    /** 操作时间 */
    private LocalDateTime operationTime;
}
