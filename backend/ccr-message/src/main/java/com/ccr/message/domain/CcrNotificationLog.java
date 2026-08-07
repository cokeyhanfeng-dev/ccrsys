package com.ccr.message.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 通知日志(ccr_notification_log)——唯一 message_key 幂等防重(§11.4)
 * status(继承): SENDING/SENT/FAILED/RECEIVED/ARCHIVED
 * send_status: PENDING(待发送,外部模块落库契约)/SUCCESS/FAILED/RETRYING
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_notification_log")
public class CcrNotificationLog extends BaseEntity {

    /** 跟踪评估主键(resolution 等外部触发可为空) */
    private Long evaluationId;

    /** 通知规则版本主键(临时消息为 0) */
    private Long ruleVersionId;

    /** 接收对象类型 */
    private String recipientType;

    /** 接收对象标识(用户id等) */
    private String recipientId;

    /** 发送渠道 */
    private String channel;

    /** 消息去重键(唯一) */
    private String messageKey;

    /** 消息内容 */
    private String messageContent;

    /** PENDING/SUCCESS/FAILED/RETRYING */
    private String sendStatus;

    /** 重试次数 */
    private Integer retryCount;

    /** 发送时间 */
    private LocalDateTime sendTime;

    /** 回执时间 */
    private LocalDateTime receiptTime;

    /** 失败原因 */
    private String errorMessage;
}
