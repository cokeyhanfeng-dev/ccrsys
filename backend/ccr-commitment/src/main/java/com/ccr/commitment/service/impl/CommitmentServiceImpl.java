package com.ccr.commitment.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.commitment.domain.CcrCommitmentMemberAlloc;
import com.ccr.commitment.domain.CcrCommitmentMetric;
import com.ccr.commitment.domain.CcrCommitmentPlan;
import com.ccr.commitment.domain.CcrTrackingEvaluation;
import com.ccr.commitment.domain.CcrTrackingPolicyVersion;
import com.ccr.commitment.domain.DwContributionMetric;
import com.ccr.commitment.mapper.CcrCommitmentMemberAllocMapper;
import com.ccr.commitment.mapper.CcrCommitmentMetricMapper;
import com.ccr.commitment.mapper.CcrCommitmentPlanMapper;
import com.ccr.commitment.mapper.CcrTrackingEvaluationMapper;
import com.ccr.commitment.mapper.CcrTrackingPolicyVersionMapper;
import com.ccr.commitment.mapper.DwContributionMetricMapper;
import com.ccr.commitment.service.CommitmentService;
import com.ccr.commitment.service.TrackingPolicyService;
import com.ccr.commitment.service.support.PlanStatusAggregator;
import com.ccr.commitment.service.support.PolicyThresholds;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.message.service.NotificationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 承诺跟踪实现
 * 达成率公式(§11.3):INCREMENT=(当前值-基线值)/承诺新增值;TARGET_BALANCE=当前值/目标值;CUMULATIVE=期间累计实际/期间目标
 * 实际值口径(D21):数仓 dw_contribution_metric 按 cust_no+metric_code 最近批次,客户级逐指标;
 * 评估阈值从冻结策略版本读取(§11.5),计划状态由全部指标结果聚合(§11.1)。
 */
@Slf4j
@Service
public class CommitmentServiceImpl implements CommitmentService {

    /** 数仓折算贡献度取值类型优先(D13) */
    private static final String VALUE_TYPE_CONTRIBUTION = "CONTRIBUTION_AMOUNT";

    @Resource
    private CcrCommitmentPlanMapper planMapper;
    @Resource
    private CcrCommitmentMetricMapper metricMapper;
    @Resource
    private CcrCommitmentMemberAllocMapper memberAllocMapper;
    @Resource
    private CcrTrackingEvaluationMapper evaluationMapper;
    @Resource
    private CcrTrackingPolicyVersionMapper policyVersionMapper;
    @Resource
    private DwContributionMetricMapper dwMetricMapper;
    @Resource
    private TrackingPolicyService trackingPolicyService;
    @Resource
    private NotificationService notificationService;
    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CcrCommitmentPlan createPlan(CcrCommitmentPlan plan, List<CcrCommitmentMetric> metrics,
                                        List<CcrCommitmentMemberAlloc> memberAllocs) {
        if (plan == null || plan.getStartDate() == null || plan.getEndDate() == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "计划起止日期必填");
        }
        if (plan.getEndDate().isBefore(plan.getStartDate())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "到期日期不得早于开始日期");
        }
        if (metrics == null || metrics.isEmpty()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "承诺指标至少一条");
        }
        for (CcrCommitmentMetric m : metrics) {
            validateMetric(m);
        }
        boolean fixedGroup = "GROUP".equals(plan.getScopeType())
                && "FIXED_ALLOCATION".equals(plan.getAllocationMode());
        if (fixedGroup && (memberAllocs == null || memberAllocs.isEmpty())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                    "集团固定分配承诺必须提供成员分配(§11.2)");
        }

        plan.setPlanNo("CMP" + IdUtil.fastSimpleUUID().substring(0, 8).toUpperCase());
        // 初始 PENDING,首次评估后进入 TRACKING(§11.1)
        plan.setStatus("PENDING");
        // 冻结跟踪策略版本(§11.5):按计划首个指标匹配,机构编码由 org_id 换算
        CcrTrackingPolicyVersion frozen = trackingPolicyService.matchPolicyVersion(
                metrics.get(0).getMetricCode(), null, resolveOrgCode(plan.getOrgId()));
        plan.setPolicyVersionId(frozen == null ? null : frozen.getId());

        planMapper.insert(plan);

        Map<String, Long> metricIdByCode = new HashMap<>();
        for (CcrCommitmentMetric m : metrics) {
            m.setPlanId(plan.getId());
            // 计算口径随承诺冻结(§11.5)
            if (m.getCalcVersion() == null || m.getCalcVersion().isBlank()) {
                m.setCalcVersion("V1");
            }
            metricMapper.insert(m);
            metricIdByCode.put(m.getMetricCode(), m.getId());
        }

        if (memberAllocs != null && !memberAllocs.isEmpty()) {
            for (CcrCommitmentMemberAlloc alloc : memberAllocs) {
                Long metricId = metricIdByCode.get(alloc.getMetricCode());
                if (metricId == null) {
                    throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(),
                            "成员分配指标编码不存在:" + alloc.getMetricCode());
                }
                alloc.setPlanId(plan.getId());
                alloc.setMetricId(metricId);
            }
            if (fixedGroup) {
                validateFixedAllocation(metrics, memberAllocs);
            }
            // GROUP_SHARED 集团共享不校验成员合计(§11.2)
            for (CcrCommitmentMemberAlloc alloc : memberAllocs) {
                memberAllocMapper.insert(alloc);
            }
            // 成员集合在承诺生效时冻结(§11.2)
            freezeMembers(plan, memberAllocs);
        }
        return plan;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CcrTrackingEvaluation evaluate(Long metricId, LocalDate dataDt, BigDecimal actualValue, String sourceBatch) {
        CcrCommitmentMetric metric = metricMapper.selectById(metricId);
        if (metric == null) {
            throw new ServiceException(404, "承诺指标不存在");
        }
        return evaluate(metric, dataDt, actualValue, sourceBatch, metric.getCalcVersion());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CcrTrackingEvaluation evaluate(Long metricId, LocalDate dataDt, BigDecimal actualValue, String sourceBatch,
                                          String calcVersion) {
        CcrCommitmentMetric metric = metricMapper.selectById(metricId);
        if (metric == null) {
            throw new ServiceException(404, "承诺指标不存在");
        }
        return evaluate(metric, dataDt, actualValue, sourceBatch, calcVersion);
    }

    private CcrTrackingEvaluation evaluate(CcrCommitmentMetric metric, LocalDate dataDt, BigDecimal actualValue,
                                           String sourceBatch, String calcVersion) {
        CcrCommitmentPlan plan = planMapper.selectById(metric.getPlanId());
        if (plan == null) {
            throw new ServiceException(404, "承诺计划不存在");
        }
        PolicyThresholds thresholds = loadThresholds(plan, metric);
        CcrTrackingEvaluation evaluation = doEvaluate(plan, metric, dataDt, actualValue, sourceBatch,
                thresholds, false, calcVersion);
        aggregatePlanStatus(plan.getId());
        return evaluation;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<CcrTrackingEvaluation> evaluatePlan(Long planId, LocalDate dataDt, String sourceBatch) {
        CcrCommitmentPlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new ServiceException(404, "承诺计划不存在");
        }
        List<CcrCommitmentMetric> metrics = metricMapper.selectList(
                new LambdaQueryWrapper<CcrCommitmentMetric>().eq(CcrCommitmentMetric::getPlanId, planId));
        List<CcrTrackingEvaluation> results = new ArrayList<>();
        for (CcrCommitmentMetric m : metrics) {
            PolicyThresholds thresholds = loadThresholds(plan, m);
            // 实际值:数仓 dw_contribution_metric 按 cust_no+metric_code 最近批次(D21)
            WarehouseData data = fetchActual(plan, m, dataDt);
            boolean dataStale = data.latestDataDt() == null
                    || ChronoUnit.DAYS.between(data.latestDataDt(), dataDt) > thresholds.toleranceDays();
            CcrTrackingEvaluation evaluation = doEvaluate(plan, m, dataDt, data.actualValue(), sourceBatch,
                    thresholds, dataStale, m.getCalcVersion());
            results.add(evaluation);
        }
        String planStatus = aggregatePlanStatus(planId);
        notifyEvaluations(planId, results);
        log.info("计划 {} 履约评估完成,聚合状态 {}", plan.getPlanNo(), planStatus);
        return results;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String aggregatePlanStatus(Long planId) {
        CcrCommitmentPlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new ServiceException(404, "承诺计划不存在");
        }
        // 终态不被聚合覆盖
        if ("TERMINATED".equals(plan.getStatus()) || "SUPERSEDED".equals(plan.getStatus())) {
            return plan.getStatus();
        }
        List<CcrCommitmentMetric> metrics = metricMapper.selectList(
                new LambdaQueryWrapper<CcrCommitmentMetric>().eq(CcrCommitmentMetric::getPlanId, planId));
        List<String> statuses = new ArrayList<>();
        boolean evaluated = false;
        for (CcrCommitmentMetric m : metrics) {
            CcrTrackingEvaluation latest = latestEvaluation(m.getId());
            if (latest != null) {
                evaluated = true;
                statuses.add(latest.getResultStatus());
            }
        }
        boolean expired = !LocalDate.now().isBefore(plan.getEndDate());
        String aggregated = PlanStatusAggregator.aggregate(statuses, expired, evaluated);
        if (!aggregated.equals(plan.getStatus())) {
            plan.setStatus(aggregated);
            planMapper.updateById(plan);
        }
        return aggregated;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CcrCommitmentPlan changeStatus(Long planId, String targetStatus, String remark) {
        CcrCommitmentPlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "承诺计划不存在");
        }
        if (!"TERMINATED".equals(targetStatus) && !"SUPERSEDED".equals(targetStatus)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "人工变迁仅支持 TERMINATED/SUPERSEDED");
        }
        if ("TERMINATED".equals(plan.getStatus()) || "SUPERSEDED".equals(plan.getStatus())) {
            throw new ServiceException(ErrorCode.FLOW_STATUS_CONFLICT.getCode(),
                    "计划已终态(" + plan.getStatus() + "),不可再变迁");
        }
        plan.setStatus(targetStatus);
        planMapper.updateById(plan);
        log.info("计划 {} 状态变迁为 {},原因:{}", plan.getPlanNo(), targetStatus, remark);
        return plan;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CcrCommitmentMetric saveTrackDesc(Long metricId, String trackDesc) {
        CcrCommitmentMetric metric = metricMapper.selectById(metricId);
        if (metric == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "承诺指标不存在");
        }
        if (StrUtil.isBlank(trackDesc)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "跟踪描述必填");
        }
        if (trackDesc.length() > 1000) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "跟踪描述长度超限(≤1000)");
        }
        // §6.4:以手工描述跟踪,覆盖式更新(保留留痕的最终态);不参与数值达成评估
        metric.setTrackDesc(trackDesc.trim());
        metricMapper.updateById(metric);
        log.info("指标 {} 跟踪描述已更新", metricId);
        return metric;
    }

    // ---------- 私有 ----------

    /** 指标校验:INCREMENT 下 target_value 存"承诺新增值",baseline/target 均须非负;承诺类型"其它"(§6.4)无数值目标,改以 track_desc 手工跟踪 */
    private void validateMetric(CcrCommitmentMetric m) {
        if (m.getMetricCode() == null || m.getMetricCode().isBlank()
                || m.getTargetType() == null || m.getTargetType().isBlank()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "指标编码与目标类型必填");
        }
        if (!Set.of("INCREMENT", "TARGET_BALANCE", "CUMULATIVE").contains(m.getTargetType())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "目标类型仅支持 INCREMENT/TARGET_BALANCE/CUMULATIVE");
        }
        if ("OTHER".equals(m.getMetricCode())) {
            // "其它"承诺:无数值达成评估,不校验 target_value
            return;
        }
        BigDecimal baseline = m.getBaselineValue() == null ? BigDecimal.ZERO : m.getBaselineValue();
        if (m.getTargetValue() == null || m.getTargetValue().compareTo(BigDecimal.ZERO) < 0
                || baseline.compareTo(BigDecimal.ZERO) < 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "基线值/目标值不得为负");
        }
        if (m.getTargetValue().compareTo(BigDecimal.ZERO) == 0) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "目标值不得为 0");
        }
    }

    /** 固定分配:每指标成员目标合计必须等于集团目标(§11.2),不等拒绝 */
    private void validateFixedAllocation(List<CcrCommitmentMetric> metrics, List<CcrCommitmentMemberAlloc> allocs) {
        for (CcrCommitmentMetric m : metrics) {
            BigDecimal sum = allocs.stream()
                    .filter(a -> m.getMetricCode().equals(a.getMetricCode()))
                    .map(a -> a.getAllocatedTarget() == null ? BigDecimal.ZERO : a.getAllocatedTarget())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(m.getTargetValue()) != 0) {
                throw new ServiceException(ErrorCode.LIMIT_INCONSISTENT.getCode(),
                        "指标 " + m.getMetricCode() + " 成员目标合计 " + sum + " 不等于集团目标 " + m.getTargetValue());
            }
        }
    }

    /** 生效时冻结成员集合快照到 member_frozen_json */
    private void freezeMembers(CcrCommitmentPlan plan, List<CcrCommitmentMemberAlloc> allocs) {
        List<Map<String, Object>> members = new ArrayList<>();
        for (CcrCommitmentMemberAlloc alloc : allocs) {
            Map<String, Object> item = new HashMap<>();
            item.put("memberCustomerNo", alloc.getMemberCustomerNo());
            item.put("metricCode", alloc.getMetricCode());
            item.put("allocatedTarget", alloc.getAllocatedTarget());
            item.put("allocatedBaseline", alloc.getAllocatedBaseline());
            members.add(item);
        }
        Map<String, Object> frozen = new HashMap<>();
        frozen.put("groupNo", plan.getGroupNo());
        frozen.put("allocationMode", plan.getAllocationMode());
        frozen.put("frozenAt", LocalDateTime.now().toString());
        frozen.put("members", members);
        plan.setMemberFrozenJson(frozen);
        planMapper.updateById(plan);
    }

    /** 读取冻结策略阈值;未冻结则实时匹配;均无则默认 */
    private PolicyThresholds loadThresholds(CcrCommitmentPlan plan, CcrCommitmentMetric metric) {
        CcrTrackingPolicyVersion version = null;
        if (plan.getPolicyVersionId() != null) {
            version = policyVersionMapper.selectById(plan.getPolicyVersionId());
        }
        if (version == null) {
            version = trackingPolicyService.matchPolicyVersion(
                    metric.getMetricCode(), null, resolveOrgCode(plan.getOrgId()));
        }
        if (version == null) {
            return PolicyThresholds.defaults();
        }
        return PolicyThresholds.from(trackingPolicyService.listThresholds(version.getId()),
                version.getDataToleranceDays());
    }

    /**
     * 数仓取数(D21):cust_no+metric_code 维度,<= dataDt 的最近批次;
     * GROUP 按成员分配合计;折算贡献度(CONTRIBUTION_AMOUNT)行优先
     */
    private WarehouseData fetchActual(CcrCommitmentPlan plan, CcrCommitmentMetric metric, LocalDate dataDt) {
        Set<String> customers = resolveCustomers(plan);
        if (customers.isEmpty()) {
            return new WarehouseData(null, null);
        }
        LocalDate latestDt = null;
        for (String custNo : customers) {
            LocalDate dt = dwMetricMapper.selectList(new LambdaQueryWrapper<DwContributionMetric>()
                            .eq(DwContributionMetric::getCustNo, custNo)
                            .eq(DwContributionMetric::getMetricCode, metric.getMetricCode())
                            .le(DwContributionMetric::getDataDt, dataDt)
                            .orderByDesc(DwContributionMetric::getDataDt)
                            .last("LIMIT 1"))
                    .stream().map(DwContributionMetric::getDataDt).findFirst().orElse(null);
            if (dt != null && (latestDt == null || dt.isAfter(latestDt))) {
                latestDt = dt;
            }
        }
        if (latestDt == null) {
            return new WarehouseData(null, null);
        }
        BigDecimal actual;
        if ("CUMULATIVE".equals(metric.getTargetType())) {
            // 期间累计实际(§11.3)
            actual = sumWarehouse(customers, metric.getMetricCode(), plan.getStartDate(), dataDt);
        } else {
            actual = sumWarehouse(customers, metric.getMetricCode(), latestDt, latestDt);
        }
        return new WarehouseData(actual, latestDt);
    }

    /** 汇总数仓指标值:折算贡献度行优先,无则全部行合计 */
    private BigDecimal sumWarehouse(Set<String> customers, String metricCode, LocalDate from, LocalDate to) {
        List<DwContributionMetric> rows = dwMetricMapper.selectList(new LambdaQueryWrapper<DwContributionMetric>()
                .in(DwContributionMetric::getCustNo, customers)
                .eq(DwContributionMetric::getMetricCode, metricCode)
                .ge(DwContributionMetric::getDataDt, from)
                .le(DwContributionMetric::getDataDt, to));
        List<DwContributionMetric> contribution = rows.stream()
                .filter(r -> VALUE_TYPE_CONTRIBUTION.equals(r.getValueType())).toList();
        List<DwContributionMetric> effective = contribution.isEmpty() ? rows : contribution;
        return effective.stream()
                .map(r -> r.getMetricValue() == null ? BigDecimal.ZERO : r.getMetricValue())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 承诺取数客户集合:单户=customerNo;成员级=memberCustomerNo;集团=成员分配客户 */
    private Set<String> resolveCustomers(CcrCommitmentPlan plan) {
        Set<String> customers = new LinkedHashSet<>();
        switch (plan.getScopeType() == null ? "CORPORATE_SINGLE" : plan.getScopeType()) {
            case "MEMBER" -> {
                if (plan.getMemberCustomerNo() != null) {
                    customers.add(plan.getMemberCustomerNo());
                }
            }
            case "GROUP" -> memberAllocMapper.selectList(new LambdaQueryWrapper<CcrCommitmentMemberAlloc>()
                            .eq(CcrCommitmentMemberAlloc::getPlanId, plan.getId()))
                    .forEach(a -> customers.add(a.getMemberCustomerNo()));
            default -> {
                if (plan.getCustomerNo() != null) {
                    customers.add(plan.getCustomerNo());
                }
            }
        }
        return customers;
    }

    /** 生成评估记录(幂等:同 plan+metric+dataDt+calcVersion 重复跳过) */
    private CcrTrackingEvaluation doEvaluate(CcrCommitmentPlan plan, CcrCommitmentMetric metric, LocalDate dataDt,
                                             BigDecimal actualValue, String sourceBatch, PolicyThresholds thresholds,
                                             boolean dataStale, String calcVersion) {
        BigDecimal achievementRatio = dataStale ? null : calcAchievement(metric, actualValue);
        BigDecimal progressRatio = calcProgress(plan, dataDt);
        boolean expired = !dataDt.isBefore(plan.getEndDate());

        CcrTrackingEvaluation evaluation = new CcrTrackingEvaluation();
        evaluation.setPlanId(plan.getId());
        evaluation.setMetricId(metric.getId());
        evaluation.setDataDt(dataDt);
        evaluation.setActualValue(actualValue == null ? BigDecimal.ZERO : actualValue);
        evaluation.setProgressRatio(progressRatio);
        evaluation.setAchievementRatio(achievementRatio);
        evaluation.setCalcVersion(calcVersion == null ? "V1" : calcVersion);
        evaluation.setSourceBatchId(sourceBatch);
        evaluation.setResultStatus(thresholds.resolveStatus(achievementRatio, expired, dataStale));
        evaluation.setRiskLevel(thresholds.resolveRisk(achievementRatio));
        if (dataStale) {
            evaluation.setRemark("数仓数据缺失或超容忍天数(" + thresholds.toleranceDays() + "天),标记 DATA_PENDING");
        }
        try {
            evaluationMapper.insert(evaluation);
        } catch (DuplicateKeyException e) {
            // 唯一键 (plan_id,metric_id,data_dt,calc_version):重复评估幂等跳过
            return evaluationMapper.selectOne(new LambdaQueryWrapper<CcrTrackingEvaluation>()
                    .eq(CcrTrackingEvaluation::getPlanId, plan.getId())
                    .eq(CcrTrackingEvaluation::getMetricId, metric.getId())
                    .eq(CcrTrackingEvaluation::getDataDt, dataDt)
                    .eq(CcrTrackingEvaluation::getCalcVersion, evaluation.getCalcVersion()));
        }
        return evaluation;
    }

    /** 评估后触发通知(§11.6):AT_RISK/EXPIRED/WATCH;通知异常不影响评估 */
    private void notifyEvaluations(Long planId, List<CcrTrackingEvaluation> results) {
        for (CcrTrackingEvaluation evaluation : results) {
            String triggerLevel = switch (evaluation.getResultStatus()) {
                case "AT_RISK" -> "AT_RISK";
                case "EXPIRED_UNMET" -> "EXPIRED";
                default -> "WATCH".equals(evaluation.getRiskLevel()) ? "WATCH" : null;
            };
            if (triggerLevel == null) {
                continue;
            }
            try {
                notificationService.notifyEvaluation(planId, evaluation.getId(), triggerLevel);
            } catch (Exception e) {
                log.warn("评估通知发送失败,planId={},evaluationId={}", planId, evaluation.getId(), e);
            }
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

    /** 达成率计算(§11.3):INCREMENT 的 target_value 为承诺新增值 */
    private BigDecimal calcAchievement(CcrCommitmentMetric metric, BigDecimal actual) {
        BigDecimal target = metric.getTargetValue();
        BigDecimal baseline = metric.getBaselineValue() == null ? BigDecimal.ZERO : metric.getBaselineValue();
        if (actual == null || target == null || target.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return switch (metric.getTargetType()) {
            case "INCREMENT" -> actual.subtract(baseline).divide(target, 6, RoundingMode.HALF_UP);
            case "TARGET_BALANCE" -> actual.divide(target, 6, RoundingMode.HALF_UP);
            case "CUMULATIVE" -> actual.divide(target, 6, RoundingMode.HALF_UP);
            default -> actual.divide(target, 6, RoundingMode.HALF_UP);
        };
    }

    /** 时间进度 = 已过天数 / 总天数 */
    private BigDecimal calcProgress(CcrCommitmentPlan plan, LocalDate dataDt) {
        long total = ChronoUnit.DAYS.between(plan.getStartDate(), plan.getEndDate());
        long passed = ChronoUnit.DAYS.between(plan.getStartDate(), dataDt);
        if (total <= 0) {
            return BigDecimal.ONE;
        }
        return BigDecimal.valueOf(Math.max(0, Math.min(1.0, (double) passed / total))).setScale(6, RoundingMode.HALF_UP);
    }

    /** 机构id → 机构编码(策略 org_code 用) */
    private String resolveOrgCode(Long orgId) {
        if (orgId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT org_code FROM ccr_sys_dept WHERE id = ? AND del_flag = '0'", orgId);
        return rows.isEmpty() ? null : (String) rows.get(0).get("org_code");
    }

    /** 数仓取数结果 */
    private record WarehouseData(BigDecimal actualValue, LocalDate latestDataDt) {
    }
}
