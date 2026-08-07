package com.ccr.common.outbox.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Outbox 告警通知落库实体(ccr_notification_log 最小写入映射)
 * 存在原因:ccr-common 不依赖 ccr-message,事件消费终态失败告警按表契约直接落库
 * (send_status=PENDING + message_key 唯一幂等),由消息模块 processPendingAndRetry 消费发送
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_notification_log")
public class OutboxAlertNotification extends BaseEntity {

    /** 跟踪评估主键(系统告警无关联,置空) */
    private Long evaluationId;

    /** 通知规则版本主键(系统内部告警不关联规则版本,置 0;表 NOT NULL 约束) */
    private Long ruleVersionId;

    /** 接收对象类型(告警固定 ROLE) */
    private String recipientType;

    /** 接收对象标识(告警固定 admin 角色编码) */
    private String recipientId;

    /** 发送渠道(告警固定 SYSTEM) */
    private String channel;

    /** 消息去重键(唯一,OUTBOX_FAIL:{eventNo}) */
    private String messageKey;

    /** 消息内容 */
    private String messageContent;

    /** PENDING(待消息模块发送)/SUCCESS/FAILED/RETRYING */
    private String sendStatus;

    /** 重试次数 */
    private Integer retryCount;

    /** 发送时间 */
    private LocalDateTime sendTime;

    /** 失败原因 */
    private String errorMessage;
}
