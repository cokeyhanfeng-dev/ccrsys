package com.ccr.snapshot.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 快照质量校验结果(ccr_snapshot_quality_result)——每项质量规则 PASS/WARN/BLOCK(§10.5)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_snapshot_quality_result")
public class CcrSnapshotQualityResult extends BaseEntity {

    /** 快照包主键 */
    private Long bundleId;

    /** 申请主键 */
    private Long applicationId;

    /** 质量规则编码 */
    private String ruleCode;

    /** PASS / WARN / BLOCK */
    private String ruleLevel;

    /** 校验对象类型 */
    private String subjectType;

    /** 校验对象标识 */
    private String subjectId;

    /** 期望值 */
    private String expectedValue;

    /** 实际值 */
    private String actualValue;

    /** 结果说明 */
    private String message;

    /** 校验时间 */
    private LocalDateTime checkedTime;

    /** 人工确认状态(§9.6 差异确认):UNCONFIRMED/CONFIRMED */
    private String confirmStatus;

    /** 人工确认人(ccr_sys_user 主键) */
    private Long confirmBy;

    /** 人工确认时间 */
    private LocalDateTime confirmTime;
}
