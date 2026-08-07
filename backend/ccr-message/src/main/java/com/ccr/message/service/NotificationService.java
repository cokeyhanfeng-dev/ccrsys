package com.ccr.message.service;

import com.ccr.message.domain.CcrNotificationLog;
import com.ccr.message.service.dto.NotificationMessage;

import java.util.List;

/**
 * 通知服务(§11.4/§11.6)
 * 对外契约:
 * 1. commitment 预警 → notifyEvaluation / sendNotification;
 * 2. resolution 核验异常 → 直接插 ccr_notification_log(send_status=PENDING,message_key 唯一),
 *    本服务 processPendingAndRetry 消费发送与重试,两边以表为契约。
 */
public interface NotificationService {

    /**
     * 按触发等级为一次评估生成并发送通知。
     * 同一 plan 同一评估周期命中多条规则时按 (plan,周期,等级,接收人) 合并为一条消息(message_key 幂等)。
     *
     * @param triggerLevel WATCH/AT_RISK/EXPIRED
     */
    List<CcrNotificationLog> notifyEvaluation(Long planId, Long evaluationId, String triggerLevel);

    /** 通用发送入口(幂等落库 + 立即尝试发送) */
    CcrNotificationLog sendNotification(NotificationMessage message);

    /** 消费 PENDING 记录、对 FAILED/RETRYING 且未超上限的记录重发,返回处理条数 */
    int processPendingAndRetry();

    /** 登记回执(RECEIVED + 回执时间) */
    CcrNotificationLog markReceipt(Long logId);
}
