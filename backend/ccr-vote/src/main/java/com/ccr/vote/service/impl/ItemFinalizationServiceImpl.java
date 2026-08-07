package com.ccr.vote.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.domain.CcrPricingItem;
import com.ccr.application.enums.ApplicationStatus;
import com.ccr.application.enums.PricingItemStatus;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.application.mapper.CcrPricingItemMapper;
import com.ccr.commitment.domain.CcrCommitmentMemberAlloc;
import com.ccr.commitment.domain.CcrCommitmentMetric;
import com.ccr.commitment.domain.CcrCommitmentPlan;
import com.ccr.commitment.service.CommitmentService;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.message.domain.CcrNotificationLog;
import com.ccr.message.mapper.CcrNotificationLogMapper;
import com.ccr.resolution.domain.CcrResolution;
import com.ccr.resolution.mapper.CcrResolutionMapper;
import com.ccr.resolution.service.ResolutionService;
import com.ccr.vote.read.ApplicationCommitmentRead;
import com.ccr.vote.read.ApplicationCommitmentReadMapper;
import com.ccr.vote.service.ItemFinalizationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 分项终态串联实现(§7.6/§7.7/§11.1)
 * 批准分项(FINAL/APPROVED_LEVEL):同事务生成决议,再按 ccr_application_commitment 指标创建承诺计划;
 * 决议/承诺异常不阻断审批主流程(记日志+落 PENDING 通知,消息模块按表契约重试)。
 * 主申请聚合(PRD V2 §7.6):全部 FINAL→FINAL;全部 REJECTED/VETOED→REJECTED;部分终态保持 ROUTING
 * (PRD V2 状态机无 PARTIAL 中间态,待全部分项出终态后再定论)。
 */
@Slf4j
@Service
public class ItemFinalizationServiceImpl implements ItemFinalizationService {

    /** 决议有效期默认一年(effective_from/effective_to 表级 NOT NULL) */
    private static final int RESOLUTION_EFFECTIVE_YEARS = 1;

    @Resource
    private CcrPricingItemMapper pricingItemMapper;
    @Resource
    private CcrApplicationMapper applicationMapper;
    @Resource
    private ResolutionService resolutionService;
    @Resource
    private CcrResolutionMapper resolutionMapper;
    @Resource
    private CommitmentService commitmentService;
    @Resource
    private ApplicationCommitmentReadMapper commitmentReadMapper;
    @Resource
    private CcrNotificationLogMapper notificationLogMapper;

    @Override
    public void afterItemTerminal(Long pricingItemId, String decisionSource) {
        CcrPricingItem item = pricingItemMapper.selectById(pricingItemId);
        if (item == null) {
            return;
        }
        if (PricingItemStatus.FINAL.getCode().equals(item.getStatus())
                || PricingItemStatus.APPROVED_LEVEL.getCode().equals(item.getStatus())) {
            createResolutionAndPlan(item, decisionSource);
        }
        aggregateApplication(item.getApplicationId());
    }

    // ---------- 决议与承诺 ----------

    /** 批准分项:生成决议(幂等,IDEMPOTENCY_REPEAT 视为已生成)并按承诺指标建计划 */
    private void createResolutionAndPlan(CcrPricingItem item, String decisionSource) {
        CcrResolution resolution;
        try {
            resolution = resolutionService.createResolution(item.getId(),
                    item.getFinalRate() != null ? item.getFinalRate() : item.getCurrentApprovalRate(),
                    item.getPricingCarrierType(),
                    StrUtil.blankToDefault(item.getCreditTrancheRef(), item.getPricingItemNo()),
                    LocalDate.now(), LocalDate.now().plusYears(RESOLUTION_EFFECTIVE_YEARS),
                    StrUtil.blankToDefault(decisionSource, "LEVEL_APPROVED"));
        } catch (ServiceException e) {
            if (ErrorCode.IDEMPOTENCY_REPEAT.getCode() == e.getCode()) {
                // 幂等:决议已存在,取原决议继续承诺计划串联
                resolution = resolutionMapper.selectOne(new LambdaQueryWrapper<CcrResolution>()
                        .eq(CcrResolution::getPricingItemId, item.getId()));
            } else {
                notifyFinalizeFailure(item, "RESOLUTION", e.getMessage());
                log.error("分项 {} 决议生成失败(不阻断主流程): {}", item.getId(), e.getMessage());
                return;
            }
        } catch (Exception e) {
            notifyFinalizeFailure(item, "RESOLUTION", e.getMessage());
            log.error("分项 {} 决议生成异常(不阻断主流程)", item.getId(), e);
            return;
        }
        if (resolution == null) {
            return;
        }
        createCommitmentPlan(item, resolution);
    }

    /** 承诺计划:指标来源 ccr_application_commitment(申请模块 03a 写入);无指标的分项跳过建计划 */
    private void createCommitmentPlan(CcrPricingItem item, CcrResolution resolution) {
        List<ApplicationCommitmentRead> rows = commitmentReadMapper.selectList(
                new LambdaQueryWrapper<ApplicationCommitmentRead>()
                        .eq(ApplicationCommitmentRead::getPricingItemId, item.getId()));
        if (rows.isEmpty()) {
            log.info("分项 {} 无承诺指标,跳过承诺计划创建", item.getId());
            return;
        }
        try {
            CcrApplication application = applicationMapper.selectById(item.getApplicationId());
            String scopeType = application == null ? null : application.getCustomerScope();

            CcrCommitmentPlan plan = new CcrCommitmentPlan();
            plan.setResolutionId(resolution.getId());
            plan.setScopeType(scopeType);
            plan.setCustomerNo(application == null ? null : application.getCustomerNo());
            plan.setGroupNo(application == null ? null : application.getGroupNo());
            // 集团场景分项定价客户即成员客户
            plan.setMemberCustomerNo("GROUP".equals(scopeType) ? item.getPricingCustomerNo() : null);
            // 集团成员分配由申请承诺指标逐成员携带,按集团共享口径建计划(不强制成员合计=集团目标)
            plan.setAllocationMode("GROUP".equals(scopeType) ? "GROUP_SHARED" : null);
            // 默认承诺跟踪周期一年
            plan.setStartDate(LocalDate.now());
            plan.setEndDate(LocalDate.now().plusYears(1));

            List<CcrCommitmentMetric> metrics = new ArrayList<>();
            List<CcrCommitmentMemberAlloc> memberAllocs = new ArrayList<>();
            for (ApplicationCommitmentRead row : rows) {
                CcrCommitmentMetric metric = new CcrCommitmentMetric();
                metric.setMetricCode(row.getMetricCode());
                metric.setMetricName(row.getMetricCode());
                metric.setTargetType(row.getTargetType());
                metric.setBaselineValue(row.getBaselineValue());
                metric.setTargetValue(row.getTargetValue());
                metric.setUnit(row.getUnit());
                metric.setMetricScope(row.getMetricScope());
                metrics.add(metric);
                // 成员级指标换算成员分配(metricCode 关联,落库时由承诺服务换算 metric_id)
                if (StrUtil.isNotBlank(row.getMemberCustomerNo())) {
                    CcrCommitmentMemberAlloc alloc = new CcrCommitmentMemberAlloc();
                    alloc.setMetricCode(row.getMetricCode());
                    alloc.setMemberCustomerNo(row.getMemberCustomerNo());
                    alloc.setAllocatedTarget(row.getTargetValue());
                    alloc.setAllocatedBaseline(row.getBaselineValue());
                    memberAllocs.add(alloc);
                }
            }
            commitmentService.createPlan(plan, metrics, memberAllocs);
            log.info("分项 {} 承诺计划创建成功,决议 {}", item.getId(), resolution.getResolutionNo());
        } catch (Exception e) {
            notifyFinalizeFailure(item, "COMMITMENT", e.getMessage());
            log.error("分项 {} 承诺计划创建异常(不阻断主流程)", item.getId(), e);
        }
    }

    /** 失败通知落库(send_status=PENDING,message_key 幂等),由消息模块 processPendingAndRetry 消费 */
    private void notifyFinalizeFailure(CcrPricingItem item, String stage, String reason) {
        String messageKey = "FINALIZE_FAIL:" + stage + ":" + item.getId();
        try {
            Long exists = notificationLogMapper.selectCount(new LambdaQueryWrapper<CcrNotificationLog>()
                    .eq(CcrNotificationLog::getMessageKey, messageKey));
            if (exists != null && exists > 0) {
                return;
            }
            CcrNotificationLog notification = new CcrNotificationLog();
            notification.setStatus("SENDING");
            // 系统内部异常通知不关联通知规则版本,置 0(表 NOT NULL 约束)
            notification.setRuleVersionId(0L);
            notification.setRecipientType("ROLE");
            notification.setRecipientId("admin");
            notification.setChannel("SYSTEM");
            notification.setMessageKey(messageKey);
            notification.setMessageContent("分项[" + item.getPricingItemNo() + "]终态串联失败(" + stage + "):" + reason);
            notification.setSendStatus("PENDING");
            notification.setRetryCount(0);
            notificationLogMapper.insert(notification);
        } catch (Exception e) {
            log.error("分项 {} 终态串联失败通知落库异常", item.getId(), e);
        }
    }

    // ---------- 主申请状态聚合 ----------

    /**
     * 主申请聚合(PRD V2 §7.6):全部 FINAL→FINAL;全部 REJECTED/VETOED→REJECTED;
     * 部分终态保持 ROUTING(PRD V2 无 PARTIAL 中间态,注释说明);已终态主申请不回写
     */
    private void aggregateApplication(Long applicationId) {
        if (applicationId == null) {
            return;
        }
        CcrApplication application = applicationMapper.selectById(applicationId);
        if (application == null) {
            return;
        }
        List<CcrPricingItem> items = pricingItemMapper.selectList(new LambdaQueryWrapper<CcrPricingItem>()
                .eq(CcrPricingItem::getApplicationId, applicationId));
        if (items.isEmpty()) {
            return;
        }
        boolean allFinal = items.stream().allMatch(i ->
                PricingItemStatus.FINAL.getCode().equals(i.getStatus())
                        || PricingItemStatus.APPROVED_LEVEL.getCode().equals(i.getStatus()));
        boolean allRejected = items.stream().allMatch(i ->
                PricingItemStatus.REJECTED.getCode().equals(i.getStatus())
                        || PricingItemStatus.VETOED.getCode().equals(i.getStatus()));

        if (allFinal) {
            application.setStatus(ApplicationStatus.FINAL.getCode());
            application.setFinalTime(LocalDateTime.now());
        } else if (allRejected) {
            // ApplicationStatus 枚举暂无 REJECTED 值,按 PRD V2 §7.6 主状态口径写字面量
            application.setStatus("REJECTED");
            application.setFinalTime(LocalDateTime.now());
        } else {
            // 部分终态:保持 ROUTING 等待其余分项;已终态(异常数据)不回退
            if (ApplicationStatus.FINAL.getCode().equals(application.getStatus())
                    || ApplicationStatus.VETOED.getCode().equals(application.getStatus())) {
                return;
            }
            if (ApplicationStatus.ROUTING.getCode().equals(application.getStatus())) {
                return;
            }
            application.setStatus(ApplicationStatus.ROUTING.getCode());
        }
        applicationMapper.updateById(application);
        log.info("主申请 {} 状态聚合为 {}", applicationId, application.getStatus());
    }
}
