package com.ccr.message.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.common.core.domain.R;
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
    @PostMapping("/send")
    public R<CcrNotificationLog> send(@RequestBody NotificationMessage message) {
        return R.ok(notificationService.sendNotification(message));
    }

    /** 日志查询(按接收人/发送状态过滤) */
    @GetMapping
    public R<List<CcrNotificationLog>> list(@RequestParam(required = false) String recipientId,
                                            @RequestParam(required = false) String sendStatus) {
        return R.ok(logMapper.selectList(new LambdaQueryWrapper<CcrNotificationLog>()
                .eq(recipientId != null && !recipientId.isBlank(), CcrNotificationLog::getRecipientId, recipientId)
                .eq(sendStatus != null && !sendStatus.isBlank(), CcrNotificationLog::getSendStatus, sendStatus)
                .orderByDesc(CcrNotificationLog::getCreateTime)
                .last("LIMIT 200")));
    }

    /** 手工触发一次重试/消费 */
    @PostMapping("/process")
    public R<Integer> process() {
        return R.ok(notificationService.processPendingAndRetry());
    }

    /** 登记回执 */
    @PostMapping("/{logId}/receipt")
    public R<CcrNotificationLog> receipt(@PathVariable Long logId) {
        return R.ok(notificationService.markReceipt(logId));
    }
}
