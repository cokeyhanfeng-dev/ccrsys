package com.ccr.commitment.service;

import com.ccr.commitment.domain.CcrCommitmentTrack;

import java.util.List;
import java.util.Map;

/**
 * 承诺跟踪服务(v2·无定时任务版,docs/28)
 * 一张跟踪表三种读法:当前完成度=实时算(数仓最新批次÷目标);到期惰性归档(读前 settleExpired);
 * 机构达成率=聚合终态行。目标类型仅 BALANCE/COUNT/RATIO,OTHER/旧值(INCREMENT/CUMULATIVE)不生成跟踪。
 */
public interface CommitmentTrackService {

    /**
     * 幂等建跟踪记录(uk_track(application_id,metric_code,member_customer_no) 冲突跳过)。
     * target_kind 映射与 org_id/manager_id/end_date 显式赋值由调用方构造完成(见 ItemFinalizationServiceImpl)。
     *
     * @param tracks 已构造好的跟踪记录(含显式 set orgId/managerId/status=TRACKING)
     */
    void createTracks(List<CcrCommitmentTrack> tracks);

    /**
     * 到期惰性结算:把 end_date&lt;today 且 TRACKING 的行按 data_dt&lt;=end_date 最近批次定案
     * (不能用结算当天最新批次,否则到期后数仓新推送篡改判定);条件更新 WHERE status='TRACKING' 幂等;
     * 截止日前无批次按未完成(记 NULL + 备注"数仓无数据")。
     *
     * @return 本次结算的行数
     */
    int settleExpired();

    /**
     * 跟踪列表(数据权限:admin/president/auditor/committee_member 全量;customer_manager 按 manager_id;
     * 其余按 org_id 归属机构)。读前先 settleExpired。TRACKING 行实时计算完成度(数仓最新批次÷目标,无批次标暂无数据),
     * 终态行读 final_* 定案字段。
     *
     * @param orgId      机构过滤(可空)
     * @param managerId  客户经理过滤(可空)
     * @param customerNo 客户号过滤(可空)
     * @param status     状态过滤(可空)
     */
    List<Map<String, Object>> listTracks(Long orgId, Long managerId, String customerNo, String status);

    /**
     * 单条跟踪详情(承诺要素 + 实时/定案信息 + 所属申请摘要)
     */
    Map<String, Object> trackDetail(Long trackId);

    /**
     * 机构达成率(执行前先 settleExpired):聚合终态行 met_rate=SUM(FINISHED_MET)/COUNT(*)、
     * avg_ratio=AVG(LEAST(final_ratio,1)),按 org_id 分组。
     *
     * @param orgId 机构过滤(可空)
     */
    List<Map<String, Object>> orgAchievement(Long orgId);
}
