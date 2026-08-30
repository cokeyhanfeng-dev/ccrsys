package com.ccr.commitment.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 承诺跟踪表(ccr_commitment_track)——v2 承诺跟踪简化(在途行 + 终态行即历史完成情况表,零定时任务)
 * 在途行不存"当前值":当前完成度查询时实时取数仓最新批次÷目标算;终态行才固化 final_* 定案字段。
 * status 仅三种:TRACKING 跟踪中/FINISHED_MET 已完成/FINISHED_UNMET 未完成
 * org_id 取申请 applicant_org_id,manager_id 取 applicant_user_id(异步 Outbox 消费必须显式 set,
 * 不能靠 MetaObjectHandler 从 session 兜底成 0)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_commitment_track")
public class CcrCommitmentTrack extends BaseEntity {

    /** 跟踪编号(TRK+yyyyMMdd+序号) */
    private String trackNo;

    /** 来源申请主键 */
    private Long applicationId;

    /** 来源申请编号 */
    private String applicationNo;

    /** 客户号(申请主客户) */
    private String customerNo;

    /** 成员客户号(集团成员承诺) */
    private String memberCustomerNo;

    /** 客户经理(取申请人 applicant_user_id) */
    private Long managerId;

    /** 稳定指标编码 */
    private String metricCode;

    /** 指标名称 */
    private String metricName;

    /** 目标类型:BALANCE 目标余额/COUNT 笔数/RATIO 比例(仅此三种) */
    private String targetKind;

    /** 申请时需要达成的目标 */
    private BigDecimal targetValue;

    /** 单位(万元/笔/%) */
    private String unit;

    /** 承诺截止日期(取 ccr_application_commitment.end_date,唯一时间基准) */
    private LocalDate endDate;

    /** 定案时指标当前值(终态写入;截止日前无批次为 NULL) */
    private BigDecimal finalActual;

    /** 定案完成比例(终态写入;final_ratio>=1 完成) */
    private BigDecimal finalRatio;

    /** 定案所用数仓批次日期(无批次为 NULL) */
    private LocalDate finalDataDt;

    /** 归档时间 */
    private LocalDateTime finishTime;

    /** 备注(如"数仓无数据") */
    private String remark;
}
