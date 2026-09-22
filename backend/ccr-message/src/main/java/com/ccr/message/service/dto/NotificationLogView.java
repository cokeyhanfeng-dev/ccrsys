package com.ccr.message.service.dto;

import com.ccr.message.domain.CcrNotificationLog;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 关联当前人员名称与归属机构；历史账号删除后日志仍保留。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NotificationLogView extends CcrNotificationLog {
    private String recipientName;
    private String recipientUsername;
    private String recipientOrgName;
}
