package com.ccr.message.service.sender;

import com.ccr.message.domain.CcrNotificationLog;

/**
 * 消息发送渠道扩展点(§11.4 通知发送)
 * 首期 SYSTEM 站内信=落库即达;新增渠道(APP推送/短信/邮件)实现本接口即可
 */
public interface MessageSender {

    /** 是否支持该渠道 */
    boolean supports(String channel);

    /**
     * 执行发送
     *
     * @throws Exception 发送失败抛出,由调用方记录 error_message 并进入重试
     */
    void send(CcrNotificationLog log) throws Exception;
}
