package com.ccr.commitment.service;

import com.ccr.commitment.domain.CcrCommitmentMemberAlloc;
import com.ccr.commitment.domain.CcrCommitmentMetric;
import com.ccr.commitment.domain.CcrCommitmentPlan;
import com.ccr.commitment.domain.CcrTrackingEvaluation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 承诺跟踪服务(§11)
 */
public interface CommitmentService {

    /**
     * 审批通过后生成承诺计划(§11.1),初始状态 PENDING,首次评估后进入 TRACKING。
     * 冻结跟踪策略版本与计算口径(§11.5);GROUP+FIXED_ALLOCATION 校验成员目标合计=集团目标(§11.2)。
     *
     * @param memberAllocs 集团成员分配(scopeType=GROUP 且 allocationMode=FIXED_ALLOCATION 时必填,
     *                     通过 metricCode 关联指标)
     */
    CcrCommitmentPlan createPlan(CcrCommitmentPlan plan, List<CcrCommitmentMetric> metrics,
                                 List<CcrCommitmentMemberAlloc> memberAllocs);

    /**
     * 单指标履约计算(§11.3 公式,显式给定实际值)
     *
     * @param metricId    承诺指标
     * @param dataDt      数据日期
     * @param actualValue 实际值(数仓最新成功批次)
     * @param sourceBatch 数仓来源批次
     */
    CcrTrackingEvaluation evaluate(Long metricId, LocalDate dataDt, BigDecimal actualValue, String sourceBatch);

    /**
     * 单指标履约计算(指定计算版本)——迟到数据修订以新 calc_version 保留修订历史(§11.4)
     */
    CcrTrackingEvaluation evaluate(Long metricId, LocalDate dataDt, BigDecimal actualValue, String sourceBatch,
                                   String calcVersion);

    /**
     * 按计划跑全部指标履约(定时任务入口,§11.4 日常履约计算)
     * 实际值从 dw_contribution_metric 按 customer_no+metric_code 取最近批次(D21);
     * 数据缺失或超容忍天数产出 DATA_PENDING,不判未履约。
     */
    List<CcrTrackingEvaluation> evaluatePlan(Long planId, LocalDate dataDt, String sourceBatch);

    /**
     * 计划状态聚合:由全部指标最新评估结果聚合(任一 AT_RISK→AT_RISK;全部 ACHIEVED→ACHIEVED;
     * 到期有未达成→EXPIRED_UNMET;有数据缺失→DATA_PENDING;否则 TRACKING)
     *
     * @return 聚合后的计划状态
     */
    String aggregatePlanStatus(Long planId);

    /**
     * 人工状态变迁(§11.1):仅支持 TERMINATED/SUPERSEDED,终态(TERMINATED/SUPERSEDED)不可再变迁
     */
    CcrCommitmentPlan changeStatus(Long planId, String targetStatus, String remark);
}
