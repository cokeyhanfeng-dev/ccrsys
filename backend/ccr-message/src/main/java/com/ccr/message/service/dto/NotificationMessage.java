package com.ccr.message.service.dto;

import lombok.Data;

/**
 * 通用通知发送请求(供 commitment 预警 / resolution 核验异常等调用)
 */
@Data
public class NotificationMessage {

    /** 关联跟踪评估主键(可空) */
    private Long evaluationId;

    /** 通知规则主键(临时消息传 0) */
    private Long ruleVersionId;

    /** 接收对象类型(ROLE/USER/BRANCH_MANAGER 等,见 ccr_notification_recipient 注释) */
    private String recipientType;

    /** 接收对象标识(用户id;recipientType 为 ROLE 时此处直接给角色编码亦可,走 resolver) */
    private String recipientId;

    /** 接收人动态解析的机构上下文(如按申请所属支行解析支行行长) */
    private Long orgId;

    /** 渠道(空=SYSTEM 站内信) */
    private String channel;

    /** 消息去重键(空则按内容哈希生成) */
    private String messageKey;

    /** 消息内容 */
    private String content;
}
