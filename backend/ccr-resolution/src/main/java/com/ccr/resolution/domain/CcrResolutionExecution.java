package com.ccr.resolution.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 决议执行与核验(ccr_resolution_execution)——回填不覆盖原决议,按业务版本幂等(§7.7)
 * 状态: ISSUED/CONTRACT_PENDING/CONTRACT_BOUND/EXECUTED/RECONCILE_EXCEPTION/CLOSED
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ccr_resolution_execution", autoResultMap = true)
public class CcrResolutionExecution extends BaseEntity {

    /** 决议主键 */
    private Long resolutionId;

    /** 合同业务标识 */
    private String contractBusinessKey;

    /** 回填正式合同号 */
    private String loanContractNo;

    /** 补充协议编号 */
    private String supplementAgreementNo;

    /** 合同执行利率(%) */
    private BigDecimal executionRate;

    /** 合同绑定时间 */
    private LocalDateTime bindTime;

    /** 执行状态(ISSUED/CONTRACT_PENDING/CONTRACT_BOUND/EXECUTED/RECONCILE_EXCEPTION/CLOSED) */
    private String executionStatus;

    /** 核验来源数仓批次 */
    private String sourceBatchId;

    /** PASS / WARN / FAILED */
    private String reconcileResult;

    /** 核验时间 */
    private LocalDateTime reconcileTime;

    /** 核验差异明细 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> differenceJson;
}
