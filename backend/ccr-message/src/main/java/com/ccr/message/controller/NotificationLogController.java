package com.ccr.message.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.common.core.domain.R;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.message.domain.CcrNotificationLog;
import com.ccr.message.mapper.CcrNotificationLogMapper;
import com.ccr.message.service.NotificationService;
import com.ccr.message.service.dto.NotificationMessage;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 通知日志接口(§11.4 发送/重试/回执)
 */
@RestController
@RequestMapping("/ccr/notification/logs")
public class NotificationLogController {

    @Resource
    private NotificationService notificationService;
    @Resource
    private CcrNotificationLogMapper logMapper;

    /** 手工发送通知(临时消息) */
    @SaCheckRole("admin")
    @PostMapping("/send")
    public R<CcrNotificationLog> send(@RequestBody NotificationMessage message) {
        return R.ok(notificationService.sendNotification(message));
    }

    /** 日志查询(按接收人/发送状态过滤) */
    @GetMapping
    public R<List<CcrNotificationLog>> list(@RequestParam(required = false) String recipientId,
                                            @RequestParam(required = false) String sendStatus) {
        boolean fullView = StpUtil.hasRole("admin") || StpUtil.hasRole("auditor");
        String effectiveRecipientId = fullView ? recipientId : StpUtil.getLoginIdAsString();
        return R.ok(logMapper.selectList(new LambdaQueryWrapper<CcrNotificationLog>()
                .eq(effectiveRecipientId != null && !effectiveRecipientId.isBlank(),
                        CcrNotificationLog::getRecipientId, effectiveRecipientId)
                .eq(sendStatus != null && !sendStatus.isBlank(), CcrNotificationLog::getSendStatus, sendStatus)
                .orderByDesc(CcrNotificationLog::getCreateTime)
                .last("LIMIT 200")));
    }

    /** 手工触发一次重试/消费 */
    @SaCheckRole("admin")
    @PostMapping("/process")
    public R<Integer> process() {
        return R.ok(notificationService.processPendingAndRetry());
    }

    /** 登记回执 */
    @PostMapping("/{logId}/receipt")
    public R<CcrNotificationLog> receipt(@PathVariable Long logId) {
        CcrNotificationLog log = logMapper.selectById(logId);
        if (log == null || "1".equals(log.getDelFlag())) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "通知记录不存在");
        }
        if (!StpUtil.hasRole("admin")
                && !StpUtil.getLoginIdAsString().equals(log.getRecipientId())) {
            throw new ServiceException(ErrorCode.FORBIDDEN.getCode(), "无权登记该通知回执");
        }
        return R.ok(notificationService.markReceipt(logId));
    }
}
