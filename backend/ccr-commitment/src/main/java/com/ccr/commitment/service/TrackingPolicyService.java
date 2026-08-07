package com.ccr.commitment.service;

import com.ccr.commitment.domain.CcrTrackingPolicy;
import com.ccr.commitment.domain.CcrTrackingPolicyVersion;
import com.ccr.commitment.domain.CcrTrackingThreshold;

import java.util.List;
import java.util.Map;

/**
 * 跟踪策略配置服务(§11.5)
 * 匹配优先级: 指标+业务+机构 > 指标+业务 > 指标默认 > 全行默认(metric_code='*')
 */
public interface TrackingPolicyService {

    /** 新建策略(含首个版本与阈值,版本状态 DRAFT) */
    CcrTrackingPolicy createPolicy(CcrTrackingPolicy policy, CcrTrackingPolicyVersion version,
                                   List<CcrTrackingThreshold> thresholds);

    /** 为策略追加新版本(含阈值,状态 DRAFT) */
    CcrTrackingPolicyVersion createVersion(Long policyId, CcrTrackingPolicyVersion version,
                                           List<CcrTrackingThreshold> thresholds);

    /** 策略/版本状态变迁(DRAFT/REVIEW/EFFECTIVE/INVALID) */
    CcrTrackingPolicy changePolicyStatus(Long policyId, String status);

    /** 版本状态变迁;置 EFFECTIVE 时校验同策略生效区间不重叠 */
    CcrTrackingPolicyVersion changeVersionStatus(Long versionId, String status);

    /** 策略列表(可按指标编码过滤) */
    List<CcrTrackingPolicy> listPolicies(String metricCode);

    /** 策略版本列表 */
    List<CcrTrackingPolicyVersion> listVersions(Long policyId);

    /** 版本阈值列表 */
    List<CcrTrackingThreshold> listThresholds(Long policyVersionId);

    /**
     * 匹配当前生效策略版本(评估/createPlan 冻结用)
     *
     * @return 命中的生效版本;无匹配返回 null(调用方走默认阈值)
     */
    CcrTrackingPolicyVersion matchPolicyVersion(String metricCode, String businessType, String orgCode);

    /**
     * 策略试算(§11.7):传入历史计划,返回命中策略/版本、阈值与预警判定
     */
    Map<String, Object> simulate(Long planId);
}
