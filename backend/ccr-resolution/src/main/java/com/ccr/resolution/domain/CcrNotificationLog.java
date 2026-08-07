package com.ccr.resolution.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 消息日志(ccr_notification_log)——核验异常通知落地(§7.7)
 * send_status=PENDING 待消息模块消费;message_key 唯一防重复
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_notification_log")
public class CcrNotificationLog extends BaseEntity {

    /** 跟踪评估主键(决议核验异常不关联评估,留空) */
    private Long evaluationId;

    /** 通知规则版本主键(决议核验异常为系统内部通知,未关联规则版本,置 0) */
    private Long ruleVersionId;

    /** 接收对象类型(CUSTOMER_MANAGER/ROLE) */
    private String recipientType;

    /** 接收对象标识(用户id/角色编码) */
    private String recipientId;

    /** 发送渠道 */
    private String channel;

    /** 消息去重键(唯一) */
    private String messageKey;

    /** 消息内容 */
    private String messageContent;

    /** 发送状态(PENDING 待消息模块消费/SUCCESS/FAILED/RETRYING) */
    private String sendStatus;

    /** 重试次数 */
    private Integer retryCount;

    /** 发送时间 */
    private LocalDateTime sendTime;

    /** 回执时间 */
    private LocalDateTime receiptTime;

    /** 错误信息 */
    private String errorMessage;
}
