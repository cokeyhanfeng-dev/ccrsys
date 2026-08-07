package com.ccr.message.controller;

import com.ccr.common.core.domain.R;
import com.ccr.message.domain.CcrNotificationRecipient;
import com.ccr.message.domain.CcrNotificationRule;
import com.ccr.message.service.NotificationRuleService;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 通知规则请求 DTO */
@Data
class RuleReq {
    private CcrNotificationRule rule;
    private List<CcrNotificationRecipient> recipients;
}

/**
 * 通知规则配置接口(§11.5/§11.6)
 */
@RestController
@RequestMapping("/ccr/notification/rules")
public class NotificationRuleController {

    @Resource
    private NotificationRuleService ruleService;

    /** 新建规则(含接收人) */
    @PostMapping
    public R<CcrNotificationRule> create(@RequestBody RuleReq req) {
        return R.ok(ruleService.createRule(req.getRule(), req.getRecipients()));
    }

    /** 更新规则并整体替换接收人 */
    @PutMapping
    public R<CcrNotificationRule> update(@RequestBody RuleReq req) {
        return R.ok(ruleService.updateRule(req.getRule(), req.getRecipients()));
    }

    /** 启用/停用 */
    @PostMapping("/{ruleId}/status")
    public R<CcrNotificationRule> changeStatus(@PathVariable Long ruleId, @RequestParam String status) {
        return R.ok(ruleService.changeStatus(ruleId, status));
    }

    /** 规则列表 */
    @GetMapping
    public R<List<CcrNotificationRule>> list(@RequestParam(required = false) String triggerLevel) {
        return R.ok(ruleService.listRules(triggerLevel));
    }

    /** 规则接收人 */
    @GetMapping("/{ruleId}/recipients")
    public R<List<CcrNotificationRecipient>> recipients(@PathVariable Long ruleId) {
        return R.ok(ruleService.listRecipients(ruleId));
    }
}
