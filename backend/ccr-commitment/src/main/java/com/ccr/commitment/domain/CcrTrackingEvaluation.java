package com.ccr.commitment.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 跟踪评估(ccr_tracking_evaluation)——每次定时校验结果,不覆盖历史(§11.4)
 * 唯一键 (plan_id, metric_id, data_dt, calc_version)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_tracking_evaluation")
public class CcrTrackingEvaluation extends BaseEntity {

    /** 承诺计划主键 */
    private Long planId;

    /** 承诺指标主键 */
    private Long metricId;

    /** 数据日期 */
    private LocalDate dataDt;

    /** 实际值(数仓最新成功批次) */
    private BigDecimal actualValue;

    /** 时间进度 */
    private BigDecimal progressRatio;

    /** 达成率 */
    private BigDecimal achievementRatio;

    /** NORMAL / WATCH / AT_RISK */
    private String riskLevel;

    /** ON_TRACK / AT_RISK / ACHIEVED / EXPIRED_UNMET / DATA_PENDING */
    private String resultStatus;

    /** 计算版本 */
    private String calcVersion;

    /** 数仓来源批次 */
    private String sourceBatchId;

    /** 备注 */
    private String remark;
}
