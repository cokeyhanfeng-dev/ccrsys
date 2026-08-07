package com.ccr.message.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.message.domain.CcrNotificationRecipient;
import com.ccr.message.domain.CcrNotificationRule;
import com.ccr.message.mapper.CcrNotificationRecipientMapper;
import com.ccr.message.mapper.CcrNotificationRuleMapper;
import com.ccr.message.service.NotificationRuleService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 通知规则配置实现
 */
@Service
public class NotificationRuleServiceImpl implements NotificationRuleService {

    private static final Set<String> TRIGGER_LEVELS = Set.of("WATCH", "AT_RISK", "EXPIRED");

    @Resource
    private CcrNotificationRuleMapper ruleMapper;
    @Resource
    private CcrNotificationRecipientMapper recipientMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CcrNotificationRule createRule(CcrNotificationRule rule, List<CcrNotificationRecipient> recipients) {
        validate(rule);
        rule.setId(null);
        rule.setRuleNo("NRL" + IdUtil.fastSimpleUUID().substring(0, 8).toUpperCase());
        rule.setStatus("ACTIVE");
        if (rule.getRepeatIntervalHours() == null) {
            rule.setRepeatIntervalHours(24);
        }
        if (rule.getMaxRepeatCount() == null) {
            rule.setMaxRepeatCount(3);
        }
        if (rule.getCoolDownHours() == null) {
            rule.setCoolDownHours(0);
        }
        ruleMapper.insert(rule);
        saveRecipients(rule.getId(), recipients);
        return rule;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CcrNotificationRule updateRule(CcrNotificationRule rule, List<CcrNotificationRecipient> recipients) {
        if (rule.getId() == null || ruleMapper.selectById(rule.getId()) == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "通知规则不存在");
        }
        validate(rule);
        ruleMapper.updateById(rule);
        if (recipients != null) {
            // 整体替换接收人(逻辑删除旧记录)
            List<CcrNotificationRecipient> old = recipientMapper.selectList(
                    new LambdaQueryWrapper<CcrNotificationRecipient>()
                            .eq(CcrNotificationRecipient::getRuleId, rule.getId()));
            old.forEach(r -> recipientMapper.deleteById(r.getId()));
            saveRecipients(rule.getId(), recipients);
        }
        return rule;
    }

    @Override
    public CcrNotificationRule changeStatus(Long ruleId, String status) {
        CcrNotificationRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "通知规则不存在");
        }
        if (!"ACTIVE".equals(status) && !"INVALID".equals(status)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "状态仅支持 ACTIVE/INVALID");
        }
        rule.setStatus(status);
        ruleMapper.updateById(rule);
        return rule;
    }

    @Override
    public List<CcrNotificationRule> listRules(String triggerLevel) {
        return ruleMapper.selectList(new LambdaQueryWrapper<CcrNotificationRule>()
                .eq(triggerLevel != null && !triggerLevel.isBlank(),
                        CcrNotificationRule::getTriggerLevel, triggerLevel)
                .orderByDesc(CcrNotificationRule::getCreateTime));
    }

    @Override
    public List<CcrNotificationRecipient> listRecipients(Long ruleId) {
        return recipientMapper.selectList(new LambdaQueryWrapper<CcrNotificationRecipient>()
                .eq(CcrNotificationRecipient::getRuleId, ruleId));
    }

    // ---------- 私有 ----------

    private void validate(CcrNotificationRule rule) {
        if (rule == null || rule.getRuleName() == null || rule.getRuleName().isBlank()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "规则名称必填");
        }
        if (!TRIGGER_LEVELS.contains(rule.getTriggerLevel())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "触发等级仅支持 WATCH/AT_RISK/EXPIRED");
        }
        if (rule.getChannel() == null || rule.getChannel().isBlank()) {
            rule.setChannel("SYSTEM");
        }
    }

    private void saveRecipients(Long ruleId, List<CcrNotificationRecipient> recipients) {
        if (recipients == null) {
            return;
        }
        for (CcrNotificationRecipient recipient : recipients) {
            if (recipient.getRecipientType() == null || recipient.getRecipientType().isBlank()) {
                throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "接收对象类型必填");
            }
            recipient.setId(null);
            recipient.setRuleId(ruleId);
            recipientMapper.insert(recipient);
        }
    }
}
