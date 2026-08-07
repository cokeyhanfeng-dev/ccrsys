package com.ccr.commitment.service;

import java.util.Map;

/**
 * 承诺查询服务(§11.8)
 * 数据权限沿用 listPlans 服务端口径(身份/角色一律取登录态,不接受传参):
 * 6人小组/总行行长/admin/审计 → 全部;客户经理 → 本人申请;普通审批人 → 本人审批过的申请
 */
public interface CommitmentQueryService {

    /**
     * 计划详情:计划 + 指标 + 每指标最新评估明细
     * 返回键: plan / items[{metric, latestEvaluation}]
     */
    Map<String, Object> planDetail(Long planId);

    /**
     * 月报(按月按机构聚合评估结果):计划数/评估数/平均达成率/风险分布/结果分布
     *
     * @param month 统计月份(YYYY-MM,空=当月)
     * @param orgId 机构(部门主键,可空=全部机构)
     */
    Map<String, Object> monthlyReport(String month, Long orgId);

    /**
     * 客户所属机构达成率(D19):dw_org_performance_snapshot 最新批次 + 本系统评估数据组装
     * 返回键: customerNo/orgCode/orgName/dataDt/statMonth/achievedAmount/expectedAmount/completionRate
     *         /orgPlanStats/customerPlanStats
     */
    Map<String, Object> orgAchievement(String customerNo);
}
