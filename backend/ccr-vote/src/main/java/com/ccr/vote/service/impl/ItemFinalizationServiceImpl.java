package com.ccr.vote.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.application.domain.CcrApplication;
import com.ccr.application.domain.CcrPricingItem;
import com.ccr.application.enums.ApplicationStatus;
import com.ccr.application.enums.PricingItemStatus;
import com.ccr.application.mapper.CcrApplicationMapper;
import com.ccr.application.mapper.CcrPricingItemMapper;
import com.ccr.commitment.domain.CcrCommitmentTrack;
import com.ccr.commitment.mapper.CommitmentTrackMapper;
import com.ccr.commitment.service.CommitmentTrackService;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.common.outbox.OutboxEventType;
import com.ccr.common.outbox.OutboxService;
import com.ccr.message.domain.CcrNotificationLog;
import com.ccr.message.mapper.CcrNotificationLogMapper;
import com.ccr.resolution.domain.CcrResolution;
import com.ccr.resolution.mapper.CcrResolutionMapper;
import com.ccr.resolution.service.ResolutionService;
import com.ccr.vote.read.ApplicationCommitmentRead;
import com.ccr.vote.mapper.ApplicationCommitmentReadMapper;
import com.ccr.vote.service.ItemFinalizationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分项终态串联实现(§3.5/§7.6/§7.7/§11.1)
 * 批准分项(FINAL/APPROVED_LEVEL):写 RESOLUTION_CREATE Outbox 事件(同事务,与终态落库同生共死),
 * 消费者异步生成决议→链式 COMMITMENT_CREATE 建承诺计划→NOTIFY 通知申请人;
 * 事件消费失败按指数退避重试,超 max_retry 置 FAILED 并告警,不阻断审批主流程;
 * 仅当事件表写入本身失败时降级为同步串联(决议/承诺同步生成,失败落 PENDING 通知)。
 * 主申请聚合(PRD V2 §7.6):全部批准→FINAL;全部否决→REJECTED;
 * 全部终态但批准/否决混合→FINAL(已批准部分生效,被否分项通过重提新申请处理);
 * 尚有分项未出终态→保持 ROUTING(PRD V2 状态机无 PARTIAL 中间态)。
 */
@Slf4j
@Service
public class ItemFinalizationServiceImpl implements ItemFinalizationService {

    /** 分项终态集合(批准侧 FINAL/APPROVED_LEVEL;否决侧 REJECTED/VETOED) */
    private static final List<String> APPROVED_STATUS = List.of(
            PricingItemStatus.FINAL.getCode(), PricingItemStatus.APPROVED_LEVEL.getCode());
    private static final List<String> REJECTED_STATUS = List.of(
            PricingItemStatus.REJECTED.getCode(), PricingItemStatus.VETOED.getCode());

    /** 决议有效期(§7.7,默认 180 天,配置 ccr.resolution.effective-days;effective_from/effective_to 表级 NOT NULL) */
    @Value("${ccr.resolution.effective-days:180}")
    private int resolutionEffectiveDays = 180;

    @Resource
    private CcrPricingItemMapper pricingItemMapper;
    @Resource
    private CcrApplicationMapper applicationMapper;
    @Resource
    private ResolutionService resolutionService;
    @Resource
    private CcrResolutionMapper resolutionMapper;
    @Resource
    private CommitmentTrackService commitmentTrackService;
    @Resource
    private CommitmentTrackMapper commitmentTrackMapper;
    @Resource
    private ApplicationCommitmentReadMapper commitmentReadMapper;
    @Resource
    private CcrNotificationLogMapper notificationLogMapper;
    @Resource
    private OutboxService outboxService;

    @Override
    public void afterItemTerminal(Long pricingItemId, String decisionSource) {
        CcrPricingItem item = pricingItemMapper.selectById(pricingItemId);
        if (item == null) {
            return;
        }
        // 小组否决(COMMITTEE_REJECT)也是终态,同样签发否决决议(决议书,不建承诺计划)
        boolean committeeReject = "COMMITTEE_REJECT".equals(decisionSource)
                && PricingItemStatus.REJECTED.getCode().equals(item.getStatus());
        if (PricingItemStatus.FINAL.getCode().equals(item.getStatus())
                || PricingItemStatus.APPROVED_LEVEL.getCode().equals(item.getStatus())
                || committeeReject) {
            // 整单化:决议按申请维度一份,仅首个终态分项触发事件;同申请其余分项终态幂等跳过(只聚合)
            Long resolutionCount = resolutionMapper.selectCount(new LambdaQueryWrapper<CcrResolution>()
                    .eq(CcrResolution::getApplicationId, item.getApplicationId()));
            if (resolutionCount != null && resolutionCount > 0) {
                log.info("申请 {} 已生成决议,整单终态事件幂等跳过(分项 {})",
                        item.getApplicationId(), item.getPricingItemNo());
            } else {
                Map<String, Object> payload = buildResolutionPayload(item, decisionSource);
                try {
                    // 同事务写事件:event_no=RESOLUTION_CREATE:app:{id} 幂等,消费者异步生成整单决议
                    outboxService.publish(OutboxEventType.RESOLUTION_CREATE,
                            "app:" + item.getApplicationId(), JSONUtil.toJsonStr(payload));
                } catch (Exception e) {
                    // 事件表写入失败才降级同步串联(内部失败落 PENDING 通知,不阻断主流程)
                    log.error("申请 {} RESOLUTION_CREATE 事件写入失败,降级同步串联", item.getApplicationId(), e);
                    processResolutionCreateSafely(item, payload);
                }
            }
        }
        aggregateApplication(item.getApplicationId());
    }

    /** 决议事件载荷(日期/利率以字符串固化,消费重试口径不变;整单化按申请维度) */
    private Map<String, Object> buildResolutionPayload(CcrPricingItem item, String decisionSource) {
        // 否决决议无最终利率(finalRate 为空);批准决议取终态利率,缺失时回退当前审批利率
        BigDecimal finalRate = "COMMITTEE_REJECT".equals(decisionSource) ? null
                : (item.getFinalRate() != null ? item.getFinalRate() : item.getCurrentApprovalRate());
        CcrApplication application = applicationMapper.selectById(item.getApplicationId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("applicationId", item.getApplicationId());
        payload.put("applicationNo", application == null ? null : application.getApplicationNo());
        payload.put("finalRate", finalRate == null ? null : finalRate.toPlainString());
        payload.put("carrierType", item.getPricingCarrierType());
        payload.put("carrierBusinessKey", application == null ? null : application.getApplicationNo());
        payload.put("effectiveFrom", LocalDate.now().toString());
        payload.put("effectiveTo", LocalDate.now().plusDays(resolutionEffectiveDays).toString());
        payload.put("decisionSource", StrUtil.blankToDefault(decisionSource, "LEVEL_APPROVED"));
        return payload;
    }

    // ---------- Outbox 事件消费入口 ----------

    @Override
    public void processResolutionCreate(Map<String, Object> payload) {
        Long applicationId = toLong(payload.get("applicationId"));
        CcrApplication application = applicationId == null ? null : applicationMapper.selectById(applicationId);
        if (application == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "申请不存在: " + applicationId);
        }
        CcrResolution resolution = createResolutionIdempotent(application, payload);
        if (resolution == null) {
            throw new ServiceException(ErrorCode.INTERNAL_ERROR.getCode(),
                    "决议生成结果为空,等待重试: 申请 " + applicationId);
        }
        // 否决决议(COMMITTEE_REJECT)不建承诺跟踪计划(§7.7 承诺计划仅对批准决议);批准决议才链式承诺计划
        if (!"COMMITTEE_REJECT".equals(StrUtil.blankToDefault(
                toStr(payload.get("decisionSource")), "LEVEL_APPROVED"))) {
            // 链式承诺计划事件(业务关键:写入失败降级同步建计划)
            Map<String, Object> commitmentPayload = new LinkedHashMap<>();
            commitmentPayload.put("applicationId", application.getId());
            commitmentPayload.put("resolutionId", resolution.getId());
            try {
                outboxService.publish(OutboxEventType.COMMITMENT_CREATE,
                        "app:" + application.getId(), JSONUtil.toJsonStr(commitmentPayload));
            } catch (Exception e) {
                log.error("申请 {} COMMITMENT_CREATE 事件写入失败,降级同步建计划", application.getId(), e);
                createCommitmentPlanSafely(application, resolution);
            }
        }
        // 决议签发通知申请人(通知为尽力而为:事件写失败仅记日志)
        publishResolutionIssuedNotify(application, resolution);
    }

    @Override
    public void processCommitmentCreate(Map<String, Object> payload) {
        Long applicationId = toLong(payload.get("applicationId"));
        Long resolutionId = toLong(payload.get("resolutionId"));
        CcrApplication application = applicationId == null ? null : applicationMapper.selectById(applicationId);
        CcrResolution resolution = resolutionId == null ? null : resolutionMapper.selectById(resolutionId);
        if (application == null || resolution == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(),
                    "承诺计划事件要素缺失: 申请 " + applicationId + " 决议 " + resolutionId);
        }
        // 幂等:该申请已存在承诺跟踪记录则跳过(event_no 防重之外的业务兜底;承诺按申请聚合,整单化一申请一份)
        Long exists = commitmentTrackMapper.selectCount(new LambdaQueryWrapper<CcrCommitmentTrack>()
                .eq(CcrCommitmentTrack::getApplicationId, applicationId));
        if (exists != null && exists > 0) {
            log.info("申请 {} 已存在承诺跟踪,幂等跳过", applicationId);
            return;
        }
        createCommitmentPlan(application, resolution);
    }

    /** 决议生成(幂等,IDEMPOTENCY_REPEAT 视为已生成取原决议);其余异常上抛由 Outbox 退避重试 */
    private CcrResolution createResolutionIdempotent(CcrApplication application, Map<String, Object> payload) {
        try {
            return resolutionService.createResolution(application.getId(),
                    payload.get("finalRate") == null ? null : new BigDecimal(payload.get("finalRate").toString()),
                    toStr(payload.get("carrierType")),
                    toStr(payload.get("carrierBusinessKey")),
                    LocalDate.parse(payload.get("effectiveFrom").toString()),
                    LocalDate.parse(payload.get("effectiveTo").toString()),
                    StrUtil.blankToDefault(toStr(payload.get("decisionSource")), "LEVEL_APPROVED"));
        } catch (ServiceException e) {
            if (ErrorCode.IDEMPOTENCY_REPEAT.getCode() == e.getCode()) {
                // 幂等:决议已存在,取原决议继续承诺计划串联
                return resolutionMapper.selectOne(new LambdaQueryWrapper<CcrResolution>()
                        .eq(CcrResolution::getApplicationId, application.getId()));
            }
            throw e;
        }
    }

    /** 决议签发通知事件(messageKey=RES_ISSUED:{resolutionId} 幂等;整单化按申请维度) */
    private void publishResolutionIssuedNotify(CcrApplication application, CcrResolution resolution) {
        try {
            Long applicantId = application == null ? null : application.getApplicantUserId();
            if (applicantId == null) {
                return;
            }
            // 否决决议(COMMITTEE_REJECT)文案按"未通过"签发
            boolean committeeReject = "COMMITTEE_REJECT".equals(resolution.getDecisionSource());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("recipientType", "USER");
            payload.put("recipientId", applicantId.toString());
            payload.put("channel", "SYSTEM");
            payload.put("messageKey", "RES_ISSUED:" + resolution.getId());
            payload.put("content", committeeReject
                    ? "申请 " + application.getApplicationNo() + " 未通过审批,决议 "
                    + resolution.getResolutionNo() + " 已签发"
                    : "申请 " + application.getApplicationNo() + " 审批通过,决议 "
                    + resolution.getResolutionNo() + " 已签发");
            outboxService.publish(OutboxEventType.NOTIFY,
                    "RES_ISSUED:" + resolution.getId(), JSONUtil.toJsonStr(payload));
        } catch (Exception e) {
            log.error("申请 {} 决议签发通知事件写入失败(不阻断)", application == null ? null : application.getId(), e);
        }
    }

    // ---------- 降级同步路径(仅事件表写入失败时进入) ----------

    /** 同步兜底:决议+承诺串联,失败落 PENDING 通知,不阻断主流程(整单化按申请维度) */
    private void processResolutionCreateSafely(CcrPricingItem item, Map<String, Object> payload) {
        CcrApplication application = applicationMapper.selectById(item.getApplicationId());
        if (application == null) {
            log.error("分项 {} 所属申请不存在,决议同步兜底中止", item.getId());
            return;
        }
        CcrResolution resolution;
        try {
            resolution = createResolutionIdempotent(application, payload);
        } catch (Exception e) {
            notifyFinalizeFailure(application, "RESOLUTION", e.getMessage());
            log.error("申请 {} 决议生成失败(降级同步,不阻断主流程)", application.getId(), e);
            return;
        }
        if (resolution == null) {
            notifyFinalizeFailure(application, "RESOLUTION", "决议生成结果为空");
            return;
        }
        createCommitmentPlanSafely(application, resolution);
        publishResolutionIssuedNotify(application, resolution);
    }

    /** 同步兜底:承诺计划,失败落 PENDING 通知,不阻断主流程 */
    private void createCommitmentPlanSafely(CcrApplication application, CcrResolution resolution) {
        try {
            createCommitmentPlan(application, resolution);
            log.info("申请 {} 承诺计划创建成功,决议 {}", application.getId(), resolution.getResolutionNo());
        } catch (Exception e) {
            notifyFinalizeFailure(application, "COMMITMENT", e.getMessage());
            log.error("申请 {} 承诺计划创建异常(不阻断主流程)", application.getId(), e);
        }
    }

    /**
     * 承诺跟踪(v2 简化,docs/28):指标来源 ccr_application_commitment,逐行转一条 TRACKING 记录;
     * 目标类型仅 BALANCE/COUNT/RATIO——旧值 TARGET_BALANCE 映射 BALANCE,INCREMENT/CUMULATIVE 与 OTHER
     * 不生成跟踪(旧行只读展示);org_id/manager_id 取申请 applicant_* 显式 set(异步 Outbox 消费不得靠 session);
     * end_date 取承诺行截止日期(唯一时间基准,消除旧"计划+1年"双口径)。
     */
    private void createCommitmentPlan(CcrApplication application, CcrResolution resolution) {
        List<ApplicationCommitmentRead> rows = commitmentReadMapper.selectList(
                new LambdaQueryWrapper<ApplicationCommitmentRead>()
                        .eq(ApplicationCommitmentRead::getApplicationId, application.getId())
                        .eq(ApplicationCommitmentRead::getDelFlag, "0"));
        if (rows.isEmpty()) {
            log.info("申请 {} 无承诺指标,跳过承诺跟踪创建", application.getId());
            return;
        }
        List<CcrCommitmentTrack> tracks = new ArrayList<>();
        for (ApplicationCommitmentRead row : rows) {
            String kind = mapTargetKind(row.getTargetType());
            if (kind == null) {
                // OTHER/INCREMENT/CUMULATIVE 不生成跟踪记录(旧行只读展示,不做数值对比)
                log.info("申请 {} 承诺指标 {} 目标类型 {} 不生成跟踪记录", application.getId(),
                        row.getMetricCode(), row.getTargetType());
                continue;
            }
            CcrCommitmentTrack track = new CcrCommitmentTrack();
            track.setApplicationId(application.getId());
            track.setApplicationNo(application.getApplicationNo());
            track.setCustomerNo(application.getCustomerNo());
            track.setMemberCustomerNo(row.getMemberCustomerNo());
            // 机构/客户经理显式 set(申请人机构/申请人),不依赖 MetaObjectHandler session 兜底
            track.setOrgId(application.getApplicantOrgId());
            track.setManagerId(application.getApplicantUserId());
            track.setMetricCode(row.getMetricCode());
            track.setMetricName(row.getMetricCode());
            track.setTargetKind(kind);
            track.setTargetValue(row.getTargetValue());
            track.setUnit(row.getUnit());
            track.setEndDate(row.getEndDate());
            track.setStatus("TRACKING");
            tracks.add(track);
        }
        if (tracks.isEmpty()) {
            log.info("申请 {} 承诺指标全为不跟踪类型,跳过", application.getId());
            return;
        }
        commitmentTrackService.createTracks(tracks);
    }

    /** 目标类型收敛映射:仅 BALANCE/COUNT/RATIO;TARGET_BALANCE→BALANCE;OTHER/INCREMENT/CUMULATIVE→null 跳过 */
    private String mapTargetKind(String targetType) {
        if (StrUtil.isBlank(targetType)) {
            return null;
        }
        return switch (targetType) {
            case "TARGET_BALANCE", "BALANCE" -> "BALANCE";
            case "COUNT" -> "COUNT";
            case "RATIO" -> "RATIO";
            default -> null;
        };
    }

    /** 失败通知落库(send_status=PENDING,message_key 幂等),由消息模块 processPendingAndRetry 消费(整单化按申请维度) */
    private void notifyFinalizeFailure(CcrApplication application, String stage, String reason) {
        String messageKey = "FINALIZE_FAIL:" + stage + ":app:" + application.getId();
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
            notification.setMessageContent("申请[" + application.getApplicationNo() + "]终态串联失败(" + stage + "):" + reason);
            notification.setSendStatus("PENDING");
            notification.setRetryCount(0);
            notificationLogMapper.insert(notification);
        } catch (Exception e) {
            log.error("申请 {} 终态串联失败通知落库异常", application.getId(), e);
        }
    }

    // ---------- 主申请状态聚合 ----------

    /**
     * 主申请聚合(PRD V2 §7.6):全部批准→FINAL;全部否决→REJECTED;
     * 全部终态但批准/否决混合→FINAL(已批准部分生效;口径:被否分项通过重提新申请处理);
     * 尚有分项未出终态→保持 ROUTING;已终态主申请不回写
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
        boolean allApproved = items.stream().allMatch(i -> APPROVED_STATUS.contains(i.getStatus()));
        boolean allRejected = items.stream().allMatch(i -> REJECTED_STATUS.contains(i.getStatus()));
        boolean allTerminal = items.stream().allMatch(i ->
                APPROVED_STATUS.contains(i.getStatus()) || REJECTED_STATUS.contains(i.getStatus()));

        if (allApproved) {
            application.setStatus(ApplicationStatus.FINAL.getCode());
            application.setFinalTime(LocalDateTime.now());
        } else if (allRejected) {
            application.setStatus(ApplicationStatus.REJECTED.getCode());
            application.setFinalTime(LocalDateTime.now());
        } else if (allTerminal) {
            // 混合终态:已批准分项生效,主申请置 FINAL;被否分项通过重提新申请处理,不再滞留 ROUTING
            application.setStatus(ApplicationStatus.FINAL.getCode());
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

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        String s = value.toString();
        return s.isBlank() ? null : Long.valueOf(s);
    }

    private static String toStr(Object value) {
        return value == null ? null : value.toString();
    }
}
