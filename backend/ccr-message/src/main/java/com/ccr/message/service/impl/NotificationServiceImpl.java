package com.ccr.message.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.message.domain.CcrNotificationLog;
import com.ccr.message.domain.CcrNotificationRecipient;
import com.ccr.message.domain.CcrNotificationRule;
import com.ccr.message.mapper.CcrNotificationLogMapper;
import com.ccr.message.mapper.CcrNotificationRecipientMapper;
import com.ccr.message.mapper.CcrNotificationRuleMapper;
import com.ccr.message.service.NotificationService;
import com.ccr.message.service.dto.NotificationMessage;
import com.ccr.message.service.recipient.RecipientContext;
import com.ccr.message.service.recipient.RecipientResolver;
import com.ccr.message.service.sender.MessageSender;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通知服务实现(§11.4/§11.6)
 * 合并口径:同一 plan 同一评估周期(data_dt)同一等级同一接收人只发一条,
 * message_key = CCR{planId}-{yyMMdd}-{level}-{md5(接收人)前16位},唯一键幂等防重。
 */
@Slf4j
@Service
public class NotificationServiceImpl implements NotificationService {

    /** 单批处理上限,防止长时间占用 */
    private static final int BATCH_LIMIT = 500;

    @Resource
    private CcrNotificationRuleMapper ruleMapper;
    @Resource
    private CcrNotificationRecipientMapper recipientMapper;
    @Resource
    private CcrNotificationLogMapper logMapper;
    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private List<RecipientResolver> recipientResolvers;
    @Resource
    private List<MessageSender> messageSenders;

    /** 失败重试上限 */
    @Value("${ccr.message.max-retry:3}")
    private int maxRetry;

    @Override
    public List<CcrNotificationLog> notifyEvaluation(Long planId, Long evaluationId, String triggerLevel) {
        if (planId == null || evaluationId == null || triggerLevel == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "planId/evaluationId/triggerLevel 必填");
        }
        Map<String, Object> plan = queryOne("SELECT * FROM ccr_commitment_plan WHERE id = ? AND del_flag = '0'", planId);
        Map<String, Object> evaluation = queryOne("SELECT * FROM ccr_tracking_evaluation WHERE id = ? AND del_flag = '0'", evaluationId);
        if (plan == null || evaluation == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "承诺计划或评估记录不存在");
        }
        List<CcrNotificationRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<CcrNotificationRule>()
                .eq(CcrNotificationRule::getTriggerLevel, triggerLevel)
                .eq(CcrNotificationRule::getStatus, "ACTIVE"));
        if (rules.isEmpty()) {
            return List.of();
        }

        RecipientContext context = buildContext(plan, evaluationId);
        Map<String, String> placeholders = buildPlaceholders(plan, evaluation);
        String cycle = String.valueOf(evaluation.get("data_dt")).substring(0, 10).replace("-", "").substring(2);

        List<CcrNotificationLog> logs = new ArrayList<>();
        for (CcrNotificationRule rule : rules) {
            List<CcrNotificationRecipient> recipients = recipientMapper.selectList(
                    new LambdaQueryWrapper<CcrNotificationRecipient>()
                            .eq(CcrNotificationRecipient::getRuleId, rule.getId())
                            .and(w -> w.isNull(CcrNotificationRecipient::getLevelCondition)
                                    .or().eq(CcrNotificationRecipient::getLevelCondition, triggerLevel)));
            // 升级路径:高等级预警按配置追加接收人类型(§11.6)
            Set<String> extraTypes = upgradeTypes(rule, triggerLevel);
            for (String extraType : extraTypes) {
                CcrNotificationRecipient extra = new CcrNotificationRecipient();
                extra.setRuleId(rule.getId());
                extra.setRecipientType(extraType);
                recipients.add(extra);
            }
            for (CcrNotificationRecipient recipient : recipients) {
                List<String> recipientIds = resolveRecipients(recipient, context);
                for (String recipientId : recipientIds) {
                    if (inCoolDown(rule, recipient.getRecipientType(), recipientId)
                            || repeatExceeded(rule, planId, recipient.getRecipientType(), recipientId)) {
                        continue;
                    }
                    NotificationMessage message = new NotificationMessage();
                    message.setEvaluationId(evaluationId);
                    message.setRuleVersionId(rule.getId());
                    message.setRecipientType(recipient.getRecipientType());
                    message.setRecipientId(recipientId);
                    message.setChannel(rule.getChannel());
                    message.setMessageKey(buildMessageKey(planId, cycle, triggerLevel,
                            recipient.getRecipientType(), recipientId));
                    message.setContent(renderTemplate(rule.getMessageTemplate(), placeholders));
                    CcrNotificationLog saved = sendNotification(message);
                    if (saved != null) {
                        logs.add(saved);
                    }
                }
            }
        }
        return logs;
    }

    @Override
    public CcrNotificationLog sendNotification(NotificationMessage message) {
        if (message.getRecipientType() == null || message.getRecipientType().isBlank()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "接收对象类型必填");
        }
        // ROLE 且 recipientId 给的是角色编码时,先解析为用户列表逐个发送
        if ("ROLE".equals(message.getRecipientType()) && message.getRecipientId() != null
                && !message.getRecipientId().chars().allMatch(Character::isDigit)) {
            List<String> ids = resolveRecipients(message.getRecipientId(), "ROLE", new RecipientContext());
            CcrNotificationLog last = null;
            for (String id : ids) {
                NotificationMessage copy = copyOf(message);
                copy.setRecipientId(id);
                copy.setMessageKey(null);
                last = sendNotification(copy);
            }
            return last;
        }
        if (message.getRecipientId() == null || message.getRecipientId().isBlank()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "接收对象标识必填");
        }
        String messageKey = message.getMessageKey();
        if (messageKey == null || messageKey.isBlank()) {
            messageKey = "MSG" + DigestUtil.md5Hex(message.getRecipientType() + "|" + message.getRecipientId()
                    + "|" + message.getEvaluationId() + "|" + message.getContent());
        }
        // message_key 幂等:重复不插
        Long exists = logMapper.selectCount(new LambdaQueryWrapper<CcrNotificationLog>()
                .eq(CcrNotificationLog::getMessageKey, messageKey));
        if (exists != null && exists > 0) {
            return null;
        }
        CcrNotificationLog notifyLog = new CcrNotificationLog();
        notifyLog.setEvaluationId(message.getEvaluationId());
        notifyLog.setRuleVersionId(message.getRuleVersionId() == null ? 0L : message.getRuleVersionId());
        notifyLog.setRecipientType(message.getRecipientType());
        notifyLog.setRecipientId(message.getRecipientId());
        notifyLog.setChannel(message.getChannel() == null || message.getChannel().isBlank() ? "SYSTEM" : message.getChannel());
        notifyLog.setMessageKey(messageKey);
        notifyLog.setMessageContent(message.getContent());
        notifyLog.setSendStatus("PENDING");
        notifyLog.setRetryCount(0);
        notifyLog.setStatus("SENDING");
        try {
            logMapper.insert(notifyLog);
        } catch (DuplicateKeyException e) {
            // 并发下同键重复:幂等跳过
            return null;
        }
        dispatch(notifyLog);
        return notifyLog;
    }

    @Override
    public int processPendingAndRetry() {
        List<CcrNotificationLog> pending = logMapper.selectList(new LambdaQueryWrapper<CcrNotificationLog>()
                .and(w -> w.eq(CcrNotificationLog::getSendStatus, "PENDING")
                        .or(w2 -> w2.in(CcrNotificationLog::getSendStatus, "FAILED", "RETRYING")
                                .lt(CcrNotificationLog::getRetryCount, maxRetry)))
                .orderByAsc(CcrNotificationLog::getId)
                .last("LIMIT " + BATCH_LIMIT));
        int processed = 0;
        for (CcrNotificationLog notifyLog : pending) {
            try {
                dispatch(notifyLog);
                processed++;
            } catch (Exception e) {
                // 单条失败(如乐观锁冲突)不中断整批
                log.warn("通知重试处理失败,id={}", notifyLog.getId(), e);
            }
        }
        return processed;
    }

    @Override
    public CcrNotificationLog markReceipt(Long logId) {
        CcrNotificationLog notifyLog = logMapper.selectById(logId);
        if (notifyLog == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "通知记录不存在");
        }
        notifyLog.setStatus("RECEIVED");
        notifyLog.setReceiptTime(LocalDateTime.now());
        logMapper.updateById(notifyLog);
        return notifyLog;
    }

    // ---------- 私有 ----------

    /** 执行发送:成功置 SENT/SUCCESS;失败累加重试次数,超上限置 FAILED */
    private void dispatch(CcrNotificationLog notifyLog) {
        MessageSender sender = messageSenders.stream()
                .filter(s -> s.supports(notifyLog.getChannel()))
                .findFirst().orElse(null);
        try {
            if (sender == null) {
                throw new ServiceException(ErrorCode.MESSAGE_SEND_FAIL.getCode(), "渠道未接入:" + notifyLog.getChannel());
            }
            sender.send(notifyLog);
            notifyLog.setSendStatus("SUCCESS");
            notifyLog.setStatus("SENT");
            notifyLog.setSendTime(LocalDateTime.now());
            notifyLog.setErrorMessage(null);
        } catch (Exception e) {
            int retryCount = notifyLog.getRetryCount() == null ? 0 : notifyLog.getRetryCount();
            retryCount++;
            notifyLog.setRetryCount(retryCount);
            notifyLog.setSendStatus(retryCount >= maxRetry ? "FAILED" : "RETRYING");
            notifyLog.setStatus("FAILED");
            notifyLog.setErrorMessage(e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage().length() > 500 ? e.getMessage().substring(0, 500) : e.getMessage());
            log.warn("通知发送失败,messageKey={},retry={}", notifyLog.getMessageKey(), retryCount, e);
        }
        logMapper.updateById(notifyLog);
    }

    private RecipientContext buildContext(Map<String, Object> plan, Long evaluationId) {
        RecipientContext context = new RecipientContext();
        context.setPlanId(toLong(plan.get("id")));
        context.setEvaluationId(evaluationId);
        context.setOrgId(toLong(plan.get("org_id")));
        context.setCustomerNo((String) plan.get("customer_no"));
        context.setGroupNo((String) plan.get("group_no"));
        context.setMemberCustomerNo((String) plan.get("member_customer_no"));
        context.setResolutionId(toLong(plan.get("resolution_id")));
        return context;
    }

    private Map<String, String> buildPlaceholders(Map<String, Object> plan, Map<String, Object> evaluation) {
        Map<String, String> map = new HashMap<>();
        map.put("planNo", str(plan.get("plan_no")));
        map.put("customerNo", str(plan.get("customer_no")));
        map.put("groupNo", str(plan.get("group_no")));
        map.put("memberCustomerNo", str(plan.get("member_customer_no")));
        map.put("endDate", str(plan.get("end_date")));
        map.put("dataDt", str(evaluation.get("data_dt")));
        map.put("actualValue", str(evaluation.get("actual_value")));
        map.put("achievementRatio", str(evaluation.get("achievement_ratio")));
        map.put("progressRatio", str(evaluation.get("progress_ratio")));
        map.put("resultStatus", str(evaluation.get("result_status")));
        map.put("riskLevel", str(evaluation.get("risk_level")));
        map.put("metricName", str(evaluation.get("metric_id") == null ? null
                : queryMetricName(toLong(evaluation.get("metric_id")))));
        return map;
    }

    private String queryMetricName(Long metricId) {
        if (metricId == null) {
            return null;
        }
        Map<String, Object> metric = queryOne(
                "SELECT metric_name FROM ccr_commitment_metric WHERE id = ? AND del_flag = '0'", metricId);
        return metric == null ? null : str(metric.get("metric_name"));
    }

    /** 解析升级路径:upgrade_rule_json 中当前等级对应的追加接收人类型 */
    @SuppressWarnings("unchecked")
    private Set<String> upgradeTypes(CcrNotificationRule rule, String triggerLevel) {
        Set<String> types = new LinkedHashSet<>();
        if (rule.getUpgradeRuleJson() == null) {
            return types;
        }
        Object value = rule.getUpgradeRuleJson().get(triggerLevel);
        if (value instanceof List<?> list) {
            for (Object item : list) {
                types.add(String.valueOf(item));
            }
        } else if (value instanceof String s) {
            types.add(s);
        }
        return types;
    }

    private List<String> resolveRecipients(CcrNotificationRecipient recipient, RecipientContext context) {
        return resolveRecipients(recipient.getRecipientValue(), recipient.getRecipientType(), context);
    }

    private List<String> resolveRecipients(String recipientValue, String recipientType, RecipientContext context) {
        return recipientResolvers.stream()
                .filter(r -> r.supports(recipientType))
                .findFirst()
                .map(r -> r.resolve(recipientType, recipientValue, context))
                .orElseGet(() -> {
                    log.warn("未找到接收人解析器,type={}", recipientType);
                    return List.of();
                });
    }

    /** 冷却期:同规则同接收人在 cool_down_hours 内已成功发送则跳过 */
    private boolean inCoolDown(CcrNotificationRule rule, String recipientType, String recipientId) {
        if (rule.getCoolDownHours() == null || rule.getCoolDownHours() <= 0) {
            return false;
        }
        Long count = logMapper.selectCount(new LambdaQueryWrapper<CcrNotificationLog>()
                .eq(CcrNotificationLog::getRuleVersionId, rule.getId())
                .eq(CcrNotificationLog::getRecipientType, recipientType)
                .eq(CcrNotificationLog::getRecipientId, recipientId)
                .eq(CcrNotificationLog::getSendStatus, "SUCCESS")
                .ge(CcrNotificationLog::getSendTime, LocalDateTime.now().minusHours(rule.getCoolDownHours())));
        return count != null && count > 0;
    }

    /** 最大提醒次数:同规则同接收人针对同一 plan 的成功提醒达上限则跳过 */
    private boolean repeatExceeded(CcrNotificationRule rule, Long planId, String recipientType, String recipientId) {
        if (rule.getMaxRepeatCount() == null || rule.getMaxRepeatCount() <= 0) {
            return false;
        }
        Long count = logMapper.selectCount(new LambdaQueryWrapper<CcrNotificationLog>()
                .eq(CcrNotificationLog::getRuleVersionId, rule.getId())
                .eq(CcrNotificationLog::getRecipientType, recipientType)
                .eq(CcrNotificationLog::getRecipientId, recipientId)
                .eq(CcrNotificationLog::getSendStatus, "SUCCESS")
                .likeRight(CcrNotificationLog::getMessageKey, "CCR" + planId + "-"));
        return count != null && count >= rule.getMaxRepeatCount();
    }

    /** 合并去重键:含 plan + 评估周期,同周期同接收人多规则命中只发一条(§11.6) */
    private String buildMessageKey(Long planId, String cycle, String triggerLevel,
                                   String recipientType, String recipientId) {
        String tail = DigestUtil.md5Hex(recipientType + "|" + recipientId).substring(0, 16);
        return "CCR" + planId + "-" + cycle + "-" + triggerLevel + "-" + tail;
    }

    private String renderTemplate(String template, Map<String, String> placeholders) {
        if (template == null || template.isBlank()) {
            template = "承诺计划{planNo}({customerNo})在{dataDt}评估结果为{resultStatus},达成率{achievementRatio},请关注。";
        }
        String content = template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            content = content.replace("{" + entry.getKey() + "}",
                    entry.getValue() == null ? "-" : entry.getValue());
        }
        return content.length() > 2000 ? content.substring(0, 2000) : content;
    }

    private NotificationMessage copyOf(NotificationMessage source) {
        NotificationMessage copy = new NotificationMessage();
        copy.setEvaluationId(source.getEvaluationId());
        copy.setRuleVersionId(source.getRuleVersionId());
        copy.setRecipientType(source.getRecipientType());
        copy.setRecipientId(source.getRecipientId());
        copy.setChannel(source.getChannel());
        copy.setMessageKey(source.getMessageKey());
        copy.setContent(source.getContent());
        return copy;
    }

    private Map<String, Object> queryOne(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Long toLong(Object value) {
        return value == null ? null : Long.valueOf(value.toString());
    }

    private String str(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        return value.toString();
    }
}
