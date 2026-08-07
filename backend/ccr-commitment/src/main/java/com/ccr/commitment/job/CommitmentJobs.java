package com.ccr.commitment.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.commitment.domain.CcrCommitmentMemberAlloc;
import com.ccr.commitment.domain.CcrCommitmentMetric;
import com.ccr.commitment.domain.CcrCommitmentPlan;
import com.ccr.commitment.domain.CcrTrackingEvaluation;
import com.ccr.commitment.domain.DwContributionMetric;
import com.ccr.commitment.mapper.CcrCommitmentMemberAllocMapper;
import com.ccr.commitment.mapper.CcrCommitmentMetricMapper;
import com.ccr.commitment.mapper.CcrCommitmentPlanMapper;
import com.ccr.commitment.mapper.CcrTrackingEvaluationMapper;
import com.ccr.commitment.mapper.DwContributionMetricMapper;
import com.ccr.commitment.service.CommitmentService;
import com.ccr.commitment.service.support.PolicyThresholds;
import com.ccr.message.service.NotificationService;
import com.ccr.message.service.dto.NotificationMessage;
import com.ccr.message.service.recipient.RecipientContext;
import com.ccr.message.service.recipient.RecipientResolver;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 承诺跟踪定时任务(§11.4)
 * 日常履约计算(每日)/到期扫描(每日)/迟到数据补算(每日)/月度汇总(每月);
 * 通知发送与重试由 ccr-message 的 NotificationRetryJob 承担。所有 cron 走配置。
 */
@Slf4j
@Component
public class CommitmentJobs {

    /** 在途计划状态(终态 ACHIEVED/EXPIRED_UNMET/TERMINATED/SUPERSEDED 不再扫描) */
    private static final List<String> ACTIVE_STATUS = List.of("PENDING", "TRACKING", "AT_RISK", "DATA_PENDING");

    @Resource
    private CommitmentService commitmentService;
    @Resource
    private CcrCommitmentPlanMapper planMapper;
    @Resource
    private CcrCommitmentMetricMapper metricMapper;
    @Resource
    private CcrCommitmentMemberAllocMapper memberAllocMapper;
    @Resource
    private CcrTrackingEvaluationMapper evaluationMapper;
    @Resource
    private DwContributionMetricMapper dwMetricMapper;
    @Resource
    private NotificationService notificationService;
    @Resource
    private List<RecipientResolver> recipientResolvers;

    /** 日常履约计算(每日):扫描在途计划,按数仓最近批次评估 */
    @Scheduled(cron = "${ccr.commitment.jobs.daily-cron:0 0 2 * * ?}")
    public void dailyEvaluate() {
        LocalDate latestDt = latestWarehouseDt();
        if (latestDt == null) {
            log.info("数仓暂无贡献度批次,跳过日常履约计算");
            return;
        }
        for (CcrCommitmentPlan plan : activePlans()) {
            try {
                commitmentService.evaluatePlan(plan.getId(), latestDt, "SCHED-DAILY");
            } catch (Exception e) {
                log.warn("日常履约计算失败,planId={}", plan.getId(), e);
            }
        }
    }

    /** 到期扫描(每日):临近到期触发提醒;到期未达成置 EXPIRED_UNMET */
    @Scheduled(cron = "${ccr.commitment.jobs.expiry-cron:0 30 2 * * ?}")
    public void expiryScan() {
        LocalDate today = LocalDate.now();
        LocalDate latestDt = latestWarehouseDt();
        int nearDays = PolicyThresholds.DEFAULT_NEAR_EXPIRY_DAYS;
        for (CcrCommitmentPlan plan : activePlans()) {
            try {
                if (!plan.getEndDate().isAfter(today)) {
                    // 已到期:重评一次,未达成由聚合逻辑置 EXPIRED_UNMET
                    if (latestDt != null) {
                        commitmentService.evaluatePlan(plan.getId(), latestDt, "SCHED-EXPIRY");
                    }
                } else if (!plan.getEndDate().isAfter(today.plusDays(nearDays))) {
                    // 临近到期:对最新评估触发 WATCH 级提醒(message_key 幂等防重)
                    CcrTrackingEvaluation latest = latestPlanEvaluation(plan.getId());
                    if (latest != null) {
                        notificationService.notifyEvaluation(plan.getId(), latest.getId(), "WATCH");
                    }
                }
            } catch (Exception e) {
                log.warn("到期扫描失败,planId={}", plan.getId(), e);
            }
        }
    }

    /**
     * 迟到数据补算(每日):新批次 data_dt 到达后补评缺失期间;
     * 同一 data_dt 数仓值与已评估值不一致时以新 calc_version 重算,保留修订历史
     */
    @Scheduled(cron = "${ccr.commitment.jobs.backfill-cron:0 0 3 * * ?}")
    public void backfillRecalc() {
        LocalDate today = LocalDate.now();
        for (CcrCommitmentPlan plan : activePlans()) {
            List<CcrCommitmentMetric> metrics = metricMapper.selectList(
                    new LambdaQueryWrapper<CcrCommitmentMetric>().eq(CcrCommitmentMetric::getPlanId, plan.getId()));
            for (CcrCommitmentMetric metric : metrics) {
                try {
                    backfillMetric(plan, metric, today);
                } catch (Exception e) {
                    log.warn("迟到数据补算失败,metricId={}", metric.getId(), e);
                }
            }
            commitmentService.aggregatePlanStatus(plan.getId());
        }
    }

    /** 月度汇总(每月):按客户/机构聚合当月评估结果并生成通知(D21 分母=同客户同指标跨计划承诺加总) */
    @Scheduled(cron = "${ccr.commitment.jobs.monthly-cron:0 0 5 1 * ?}")
    public void monthlySummary() {
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        List<CcrTrackingEvaluation> monthEvals = evaluationMapper.selectList(
                new LambdaQueryWrapper<CcrTrackingEvaluation>()
                        .ge(CcrTrackingEvaluation::getDataDt, monthStart));
        if (monthEvals.isEmpty()) {
            return;
        }
        Map<Long, CcrCommitmentPlan> planCache = new HashMap<>();
        Map<Long, CcrCommitmentMetric> metricCache = new HashMap<>();
        // key: orgId|customer|metricCode → 聚合行
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        Map<Long, List<String>> orgCustomers = new LinkedHashMap<>();
        for (CcrTrackingEvaluation evaluation : monthEvals) {
            CcrCommitmentPlan plan = planCache.computeIfAbsent(evaluation.getPlanId(), planMapper::selectById);
            CcrCommitmentMetric metric = metricCache.computeIfAbsent(evaluation.getMetricId(), metricMapper::selectById);
            if (plan == null || metric == null) {
                continue;
            }
            String customer = switch (plan.getScopeType() == null ? "" : plan.getScopeType()) {
                case "GROUP" -> "G:" + plan.getGroupNo();
                case "MEMBER" -> plan.getMemberCustomerNo();
                default -> plan.getCustomerNo();
            };
            String key = plan.getOrgId() + "|" + customer + "|" + metric.getMetricCode();
            Map<String, Object> row = rows.computeIfAbsent(key, k -> {
                Map<String, Object> r = new HashMap<>();
                r.put("orgId", plan.getOrgId());
                r.put("customer", customer);
                r.put("metricCode", metric.getMetricCode());
                r.put("committed", BigDecimal.ZERO);
                r.put("actual", null);
                r.put("latestDt", null);
                return r;
            });
            // 分母:同一客户跨计划同 metric_code 承诺加总(D21)
            row.put("committed", ((BigDecimal) row.get("committed")).add(
                    metric.getTargetValue() == null ? BigDecimal.ZERO : metric.getTargetValue()));
            // 分子:数仓最近批次实际值(取最新 data_dt 的评估实际值)
            LocalDate dt = evaluation.getDataDt();
            if (row.get("latestDt") == null || dt.isAfter((LocalDate) row.get("latestDt"))) {
                row.put("latestDt", dt);
                row.put("actual", evaluation.getActualValue());
            }
            orgCustomers.computeIfAbsent(plan.getOrgId(), k -> new ArrayList<>());
            if (!orgCustomers.get(plan.getOrgId()).contains(key)) {
                orgCustomers.get(plan.getOrgId()).add(key);
            }
        }
        // 按机构生成汇总通知(§13.4 发送对象:该机构支行行长 + 部门总经理 dept_gm)
        for (Map.Entry<Long, List<String>> entry : orgCustomers.entrySet()) {
            Long orgId = entry.getKey();
            StringBuilder content = new StringBuilder("承诺月度汇总(").append(monthStart).append("):");
            for (String key : entry.getValue()) {
                Map<String, Object> row = rows.get(key);
                BigDecimal committed = (BigDecimal) row.get("committed");
                BigDecimal actual = (BigDecimal) row.get("actual");
                String ratio = committed.compareTo(BigDecimal.ZERO) == 0 || actual == null ? "-"
                        : actual.divide(committed, 4, RoundingMode.HALF_UP).toString();
                content.append("\n客户 ").append(row.get("customer"))
                        .append(" 指标 ").append(row.get("metricCode"))
                        .append(" 承诺合计 ").append(committed)
                        .append(" 实际 ").append(actual == null ? "-" : actual)
                        .append(" 达成率 ").append(ratio);
            }
            RecipientContext context = new RecipientContext();
            context.setOrgId(orgId);
            List<String> managerIds = recipientResolvers.stream()
                    .filter(r -> r.supports("BRANCH_MANAGER")).findFirst()
                    .map(r -> r.resolve("BRANCH_MANAGER", null, context))
                    .orElse(List.of());
            for (String userId : managerIds) {
                NotificationMessage message = new NotificationMessage();
                message.setRuleVersionId(0L);
                message.setRecipientType("BRANCH_MANAGER");
                message.setRecipientId(userId);
                message.setChannel("SYSTEM");
                message.setContent(content.toString());
                notificationService.sendNotification(message);
            }
            // §13.4 推送至支行行长与部门:补部门总经理(dept_gm)角色接收人
            List<String> deptGmIds = recipientResolvers.stream()
                    .filter(r -> r.supports("DEPT_GM")).findFirst()
                    .map(r -> r.resolve("DEPT_GM", null, context))
                    .orElse(List.of());
            for (String userId : deptGmIds) {
                NotificationMessage message = new NotificationMessage();
                message.setRuleVersionId(0L);
                message.setRecipientType("DEPT_GM");
                message.setRecipientId(userId);
                message.setChannel("SYSTEM");
                message.setContent(content.toString());
                notificationService.sendNotification(message);
            }
        }
    }

    // ---------- 私有 ----------

    /** 单指标补算:缺失期间补评;已评估期间值变化则以新 calc_version 重算 */
    private void backfillMetric(CcrCommitmentPlan plan, CcrCommitmentMetric metric, LocalDate today) {
        // 数仓该指标全部批次值(按 data_dt 聚合,折算贡献度行优先)
        TreeMap<LocalDate, BigDecimal> warehouse = warehouseSeries(plan, metric, today);
        if (warehouse.isEmpty()) {
            return;
        }
        // 已评估记录: dataDt → (实际值, 最大calcVersion序号)
        Map<LocalDate, BigDecimal> evaluatedValue = new HashMap<>();
        Map<LocalDate, Integer> evaluatedVersion = new HashMap<>();
        evaluationMapper.selectList(new LambdaQueryWrapper<CcrTrackingEvaluation>()
                        .eq(CcrTrackingEvaluation::getMetricId, metric.getId()))
                .forEach(e -> {
                    evaluatedValue.put(e.getDataDt(), e.getActualValue());
                    evaluatedVersion.merge(e.getDataDt(), parseVersion(e.getCalcVersion()), Math::max);
                });
        for (Map.Entry<LocalDate, BigDecimal> entry : warehouse.entrySet()) {
            LocalDate dt = entry.getKey();
            BigDecimal actual = "CUMULATIVE".equals(metric.getTargetType())
                    ? cumulativeTo(warehouse, dt) : entry.getValue();
            if (!evaluatedValue.containsKey(dt)) {
                // 新批次期间:补评
                commitmentService.evaluate(metric.getId(), dt, actual, "SCHED-BACKFILL");
            } else if (actual.compareTo(evaluatedValue.get(dt)) != 0) {
                // 迟到数据修订:新 calc_version 保留修订历史
                String nextVersion = "V" + (evaluatedVersion.get(dt) + 1);
                commitmentService.evaluate(metric.getId(), dt, actual, "SCHED-BACKFILL-REVISE", nextVersion);
            }
        }
    }

    /** 数仓序列: 计划期间内 cust+metric 按 data_dt 聚合(CONTRIBUTION_AMOUNT 行优先) */
    private TreeMap<LocalDate, BigDecimal> warehouseSeries(CcrCommitmentPlan plan, CcrCommitmentMetric metric,
                                                           LocalDate today) {
        List<String> customers = planCustomers(plan);
        TreeMap<LocalDate, BigDecimal> series = new TreeMap<>();
        if (customers.isEmpty()) {
            return series;
        }
        List<DwContributionMetric> rows = dwMetricMapper.selectList(new LambdaQueryWrapper<DwContributionMetric>()
                .in(DwContributionMetric::getCustNo, customers)
                .eq(DwContributionMetric::getMetricCode, metric.getMetricCode())
                .ge(DwContributionMetric::getDataDt, plan.getStartDate())
                .le(DwContributionMetric::getDataDt, today));
        Map<LocalDate, List<DwContributionMetric>> byDt = new HashMap<>();
        rows.forEach(r -> byDt.computeIfAbsent(r.getDataDt(), k -> new ArrayList<>()).add(r));
        byDt.forEach((dt, list) -> {
            List<DwContributionMetric> contribution = list.stream()
                    .filter(r -> "CONTRIBUTION_AMOUNT".equals(r.getValueType())).toList();
            List<DwContributionMetric> effective = contribution.isEmpty() ? list : contribution;
            series.put(dt, effective.stream()
                    .map(r -> r.getMetricValue() == null ? BigDecimal.ZERO : r.getMetricValue())
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
        });
        return series;
    }

    private List<String> planCustomers(CcrCommitmentPlan plan) {
        return switch (plan.getScopeType() == null ? "CORPORATE_SINGLE" : plan.getScopeType()) {
            case "MEMBER" -> plan.getMemberCustomerNo() == null ? List.of() : List.of(plan.getMemberCustomerNo());
            case "GROUP" -> groupMembers(plan.getId());
            default -> plan.getCustomerNo() == null ? List.of() : List.of(plan.getCustomerNo());
        };
    }

    /** 集团计划成员客户(冻结分配集合) */
    private List<String> groupMembers(Long planId) {
        return memberAllocMapper.selectList(new LambdaQueryWrapper<CcrCommitmentMemberAlloc>()
                        .eq(CcrCommitmentMemberAlloc::getPlanId, planId))
                .stream().map(CcrCommitmentMemberAlloc::getMemberCustomerNo).distinct().toList();
    }

    private BigDecimal cumulativeTo(TreeMap<LocalDate, BigDecimal> series, LocalDate dt) {
        return series.headMap(dt, true).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private int parseVersion(String calcVersion) {
        if (calcVersion != null && calcVersion.startsWith("V")) {
            try {
                return Integer.parseInt(calcVersion.substring(1));
            } catch (NumberFormatException ignored) {
                // 非标准版本号按 V1 处理
            }
        }
        return 1;
    }

    private LocalDate latestWarehouseDt() {
        return dwMetricMapper.selectList(new LambdaQueryWrapper<DwContributionMetric>()
                        .orderByDesc(DwContributionMetric::getDataDt)
                        .last("LIMIT 1"))
                .stream().map(DwContributionMetric::getDataDt).findFirst().orElse(null);
    }

    private List<CcrCommitmentPlan> activePlans() {
        return planMapper.selectList(new LambdaQueryWrapper<CcrCommitmentPlan>()
                .in(CcrCommitmentPlan::getStatus, ACTIVE_STATUS));
    }

    private CcrTrackingEvaluation latestPlanEvaluation(Long planId) {
        return evaluationMapper.selectList(new LambdaQueryWrapper<CcrTrackingEvaluation>()
                        .eq(CcrTrackingEvaluation::getPlanId, planId)
                        .orderByDesc(CcrTrackingEvaluation::getDataDt)
                        .orderByDesc(CcrTrackingEvaluation::getId)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }
}
