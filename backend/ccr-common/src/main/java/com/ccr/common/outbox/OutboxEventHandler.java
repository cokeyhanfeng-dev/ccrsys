package com.ccr.common.outbox;

import com.ccr.common.outbox.domain.CcrOutboxEvent;

/**
 * Outbox 事件处理器(按 eventType 分发)
 * 实现注册在能访问对应业务 Service 的模块(ccr-admin),避免 Maven 循环依赖;
 * 处理必须幂等(event_no 防重 + 处理器自身幂等),处理失败抛异常由消费者退避重试
 */
public interface OutboxEventHandler {

    /** 处理的事件类型(见 {@link OutboxEventType}) */
    String eventType();

    /** 处理事件;失败抛异常触发重试,超过 max_retry 由消费者置 FAILED 并告警 */
    void handle(CcrOutboxEvent event);
}
