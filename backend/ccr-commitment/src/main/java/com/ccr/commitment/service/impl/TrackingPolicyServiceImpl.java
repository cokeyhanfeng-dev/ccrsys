package com.ccr.commitment.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.commitment.domain.CcrCommitmentMetric;
import com.ccr.commitment.domain.CcrCommitmentPlan;
import com.ccr.commitment.domain.CcrTrackingEvaluation;
import com.ccr.commitment.domain.CcrTrackingPolicy;
import com.ccr.commitment.domain.CcrTrackingPolicyVersion;
import com.ccr.commitment.domain.CcrTrackingThreshold;
import com.ccr.commitment.mapper.CcrCommitmentMetricMapper;
import com.ccr.commitment.mapper.CcrCommitmentPlanMapper;
import com.ccr.commitment.mapper.CcrTrackingEvaluationMapper;
import com.ccr.commitment.mapper.CcrTrackingPolicyMapper;
import com.ccr.commitment.mapper.CcrTrackingPolicyVersionMapper;
import com.ccr.commitment.mapper.CcrTrackingThresholdMapper;
import com.ccr.commitment.service.TrackingPolicyService;
import com.ccr.commitment.service.support.PolicyThresholds;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跟踪策略配置实现(§11.5)
 * 匹配优先级: 指标+业务+机构 > 指标+业务 > 指标默认 > 全行默认
 */
@Service
public class TrackingPolicyServiceImpl implements TrackingPolicyService {

    /** 全行默认策略的指标编码 */
    public static final String GLOBAL_METRIC = "*";

    private static final Set<String> POLICY_STATUS = Set.of("DRAFT", "REVIEW", "EFFECTIVE", "INVALID");

    @Resource
    private CcrTrackingPolicyMapper policyMapper;
    @Resource
    private CcrTrackingPolicyVersionMapper versionMapper;
    @Resource
    private CcrTrackingThresholdMapper thresholdMapper;
    @Resource
    private CcrCommitmentPlanMapper planMapper;
    @Resource
    private CcrCommitmentMetricMapper metricMapper;
    @Resource
    private CcrTrackingEvaluationMapper evaluationMapper;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CcrTrackingPolicy createPolicy(CcrTrackingPolicy policy, CcrTrackingPolicyVersion version,
                                          List<CcrTrackingThreshold> thresholds) {
        validatePolicy(policy);
        policy.setId(null);
        policy.setPolicyNo("TPY" + IdUtil.fastSimpleUUID().substring(0, 8).toUpperCase());
        policy.setStatus("DRAFT");
        if (policy.getPriority() == null) {
            policy.setPriority(0);
        }
        policyMapper.insert(policy);
        createVersion(policy.getId(), version, thresholds);
        return policy;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CcrTrackingPolicyVersion createVersion(Long policyId, CcrTrackingPolicyVersion version,
                                                  List<CcrTrackingThreshold> thresholds) {
        if (policyMapper.selectById(policyId) == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "跟踪策略不存在");
        }
        if (version == null || version.getVersionCode() == null || version.getVersionCode().isBlank()
                || version.getEffectiveFrom() == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "版本号与生效时间必填");
        }
        version.setId(null);
        version.setPolicyId(policyId);
        version.setStatus("DRAFT");
        if (version.getCheckFrequency() == null) {
            version.setCheckFrequency("DAILY");
        }
        if (version.getDataToleranceDays() == null) {
            version.setDataToleranceDays(PolicyThresholds.DEFAULT_TOLERANCE_DAYS);
        }
        versionMapper.insert(version);
        saveThresholds(version.getId(), thresholds);
        return version;
    }

    @Override
    public CcrTrackingPolicy changePolicyStatus(Long policyId, String status) {
        CcrTrackingPolicy policy = policyMapper.selectById(policyId);
        if (policy == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "跟踪策略不存在");
        }
        if (!POLICY_STATUS.contains(status)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "状态仅支持 DRAFT/REVIEW/EFFECTIVE/INVALID");
        }
        policy.setStatus(status);
        policyMapper.updateById(policy);
        return policy;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CcrTrackingPolicyVersion changeVersionStatus(Long versionId, String status) {
        CcrTrackingPolicyVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "策略版本不存在");
        }
        if (!POLICY_STATUS.contains(status)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "状态仅支持 DRAFT/REVIEW/EFFECTIVE/INVALID");
        }
        if ("EFFECTIVE".equals(status)) {
            // 生效区间不重叠(§11.5 表注释)
            List<CcrTrackingPolicyVersion> effective = versionMapper.selectList(
                    new LambdaQueryWrapper<CcrTrackingPolicyVersion>()
                            .eq(CcrTrackingPolicyVersion::getPolicyId, version.getPolicyId())
                            .eq(CcrTrackingPolicyVersion::getStatus, "EFFECTIVE")
                            .ne(CcrTrackingPolicyVersion::getId, versionId));
            for (CcrTrackingPolicyVersion other : effective) {
                boolean otherEndsBefore = other.getEffectiveTo() != null
                        && !other.getEffectiveTo().isAfter(version.getEffectiveFrom());
                boolean versionEndsBefore = version.getEffectiveTo() != null
                        && !version.getEffectiveTo().isAfter(other.getEffectiveFrom());
                if (!otherEndsBefore && !versionEndsBefore) {
                    throw new ServiceException(ErrorCode.DATA_VERSION_CONFLICT.getCode(),
                            "与生效中版本 V" + other.getVersionCode() + " 生效区间重叠,请先失效旧版本");
                }
            }
        }
        version.setStatus(status);
        versionMapper.updateById(version);
        return version;
    }

    @Override
    public List<CcrTrackingPolicy> listPolicies(String metricCode) {
        return policyMapper.selectList(new LambdaQueryWrapper<CcrTrackingPolicy>()
                .eq(metricCode != null && !metricCode.isBlank(), CcrTrackingPolicy::getMetricCode, metricCode)
                .orderByAsc(CcrTrackingPolicy::getMetricCode)
                .orderByDesc(CcrTrackingPolicy::getPriority));
    }

    @Override
    public List<CcrTrackingPolicyVersion> listVersions(Long policyId) {
        return versionMapper.selectList(new LambdaQueryWrapper<CcrTrackingPolicyVersion>()
                .eq(CcrTrackingPolicyVersion::getPolicyId, policyId)
                .orderByDesc(CcrTrackingPolicyVersion::getEffectiveFrom));
    }

    @Override
    public List<CcrTrackingThreshold> listThresholds(Long policyVersionId) {
        return thresholdMapper.selectList(new LambdaQueryWrapper<CcrTrackingThreshold>()
                .eq(CcrTrackingThreshold::getPolicyVersionId, policyVersionId));
    }

    @Override
    public CcrTrackingPolicyVersion matchPolicyVersion(String metricCode, String businessType, String orgCode) {
        List<CcrTrackingPolicy> policies = policyMapper.selectList(new LambdaQueryWrapper<CcrTrackingPolicy>()
                .eq(CcrTrackingPolicy::getStatus, "EFFECTIVE"));
        CcrTrackingPolicy matched = policies.stream()
                .map(p -> Map.entry(p, specificity(p.getMetricCode(), p.getBusinessType(), p.getOrgCode(),
                        metricCode, businessType, orgCode)))
                .filter(e -> e.getValue() >= 0)
                .max(Comparator.<Map.Entry<CcrTrackingPolicy, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparingInt(e -> e.getKey().getPriority() == null ? 0 : e.getKey().getPriority()))
                .map(Map.Entry::getKey)
                .orElse(null);
        if (matched == null) {
            return null;
        }
        // 取当前生效区间内的版本
        LocalDateTime now = LocalDateTime.now();
        return versionMapper.selectList(new LambdaQueryWrapper<CcrTrackingPolicyVersion>()
                        .eq(CcrTrackingPolicyVersion::getPolicyId, matched.getId())
                        .eq(CcrTrackingPolicyVersion::getStatus, "EFFECTIVE")
                        .le(CcrTrackingPolicyVersion::getEffectiveFrom, now)
                        .and(w -> w.isNull(CcrTrackingPolicyVersion::getEffectiveTo)
                                .or().gt(CcrTrackingPolicyVersion::getEffectiveTo, now))
                        .orderByDesc(CcrTrackingPolicyVersion::getEffectiveFrom))
                .stream().findFirst().orElse(null);
    }

    @Override
    public Map<String, Object> simulate(Long planId) {
        CcrCommitmentPlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "承诺计划不存在");
        }
        List<CcrCommitmentMetric> metrics = metricMapper.selectList(
                new LambdaQueryWrapper<CcrCommitmentMetric>().eq(CcrCommitmentMetric::getPlanId, planId));
        String orgCode = resolveOrgCode(plan.getOrgId());

        List<Map<String, Object>> metricResults = new ArrayList<>();
        for (CcrCommitmentMetric metric : metrics) {
            CcrTrackingPolicyVersion version = matchPolicyVersion(metric.getMetricCode(), null, orgCode);
            CcrTrackingPolicy policy = version == null ? null : policyMapper.selectById(version.getPolicyId());
            List<CcrTrackingThreshold> thresholds = version == null ? List.of() : listThresholds(version.getId());
            PolicyThresholds pts = version == null
                    ? PolicyThresholds.defaults()
                    : PolicyThresholds.from(thresholds, version.getDataToleranceDays());

            CcrTrackingEvaluation latest = latestEvaluation(metric.getId());
            Map<String, Object> item = new HashMap<>();
            item.put("metricId", metric.getId());
            item.put("metricCode", metric.getMetricCode());
            item.put("matchedPolicyNo", policy == null ? null : policy.getPolicyNo());
            item.put("matchedPolicyName", policy == null ? null : policy.getPolicyName());
            item.put("matchedVersionCode", version == null ? null : version.getVersionCode());
            item.put("achieveLine", pts.achieveLine());
            item.put("atRiskLine", pts.atRiskLine());
            item.put("nearExpiryDays", pts.nearExpiryDays());
            item.put("toleranceDays", pts.toleranceDays());
            if (latest != null) {
                boolean expired = !latest.getDataDt().isBefore(plan.getEndDate());
                item.put("dataDt", latest.getDataDt());
                item.put("achievementRatio", latest.getAchievementRatio());
                item.put("progressRatio", latest.getProgressRatio());
                item.put("judgeResult", pts.resolveStatus(latest.getAchievementRatio(), expired, false));
                item.put("judgeRiskLevel", pts.resolveRisk(latest.getAchievementRatio()));
            } else {
                item.put("judgeResult", "NO_EVALUATION");
            }
            metricResults.add(item);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("planId", planId);
        result.put("planNo", plan.getPlanNo());
        result.put("frozenPolicyVersionId", plan.getPolicyVersionId());
        result.put("metrics", metricResults);
        return result;
    }

    // ---------- 私有 ----------

    /**
     * 匹配度评分(纯函数,便于单测):
     * 指标精确 +4,业务精确 +2,机构精确 +1;任一维度不满足返回 -1。
     * 指标为 * 视为全行默认(仅当请求指标无精确匹配时兜底)。
     */
    static int specificity(String policyMetric, String policyBiz, String policyOrg,
                           String metric, String biz, String org) {
        int score = 0;
        if (GLOBAL_METRIC.equals(policyMetric)) {
            // 全行默认:不加分
        } else if (policyMetric != null && policyMetric.equals(metric)) {
            score += 4;
        } else {
            return -1;
        }
        if (isBlank(policyBiz)) {
            // 不限业务:不加分
        } else if (policyBiz.equals(biz)) {
            score += 2;
        } else {
            return -1;
        }
        if (isBlank(policyOrg)) {
            // 通用机构:不加分
        } else if (policyOrg.equals(org)) {
            score += 1;
        } else {
            return -1;
        }
        return score;
    }

    private void validatePolicy(CcrTrackingPolicy policy) {
        if (policy == null || policy.getPolicyName() == null || policy.getPolicyName().isBlank()
                || policy.getMetricCode() == null || policy.getMetricCode().isBlank()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "策略名称与指标编码必填(全行默认用 *)");
        }
    }

    private void saveThresholds(Long policyVersionId, List<CcrTrackingThreshold> thresholds) {
        if (thresholds == null) {
            return;
        }
        for (CcrTrackingThreshold threshold : thresholds) {
            if (threshold.getThresholdType() == null || threshold.getThresholdValue() == null
                    || threshold.getRiskLevel() == null || threshold.getCompareOperator() == null) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "阈值类型/数值/等级/比较符必填");
            }
            threshold.setId(null);
            threshold.setPolicyVersionId(policyVersionId);
            thresholdMapper.insert(threshold);
        }
    }

    private CcrTrackingEvaluation latestEvaluation(Long metricId) {
        return evaluationMapper.selectList(new LambdaQueryWrapper<CcrTrackingEvaluation>()
                        .eq(CcrTrackingEvaluation::getMetricId, metricId)
                        .orderByDesc(CcrTrackingEvaluation::getDataDt)
                        .orderByDesc(CcrTrackingEvaluation::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    /** 机构id → 机构编码(策略 org_code 用) */
    private String resolveOrgCode(Long orgId) {
        if (orgId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT dept_code FROM ccr_sys_dept WHERE id = ? AND del_flag = '0'", orgId);
        return rows.isEmpty() ? null : (String) rows.get(0).get("dept_code");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
