package com.ccr.message.service;

import com.ccr.message.domain.CcrNotificationRecipient;
import com.ccr.message.domain.CcrNotificationRule;

import java.util.List;

/**
 * 通知规则配置服务(§11.5/§11.6)
 */
public interface NotificationRuleService {

    /** 新建规则(含接收人) */
    CcrNotificationRule createRule(CcrNotificationRule rule, List<CcrNotificationRecipient> recipients);

    /** 更新规则并整体替换接收人 */
    CcrNotificationRule updateRule(CcrNotificationRule rule, List<CcrNotificationRecipient> recipients);

    /** 启用/停用 */
    CcrNotificationRule changeStatus(Long ruleId, String status);

    /** 规则列表(可按触发等级过滤) */
    List<CcrNotificationRule> listRules(String triggerLevel);

    /** 规则接收人 */
    List<CcrNotificationRecipient> listRecipients(Long ruleId);
}
