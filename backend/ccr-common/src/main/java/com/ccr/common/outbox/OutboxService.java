package com.ccr.common.outbox;

import com.ccr.common.outbox.domain.CcrOutboxEvent;

/**
 * Outbox 事件发布服务(§3.5)
 * 调用方在业务本地事务内发布,与业务写入同库同事务,保证"业务成功则事件必达"
 */
public interface OutboxService {

    /**
     * 发布事件(同事务写入 ccr_outbox_event,status=PENDING)
     * event_no = eventType + ":" + bizKey 确定性生成,uk_event_no 唯一约束保证重复发布幂等
     * (重复发布返回已存在事件,不抛异常)
     *
     * @param eventType   事件类型(见 {@link OutboxEventType})
     * @param bizKey      业务幂等键(如同一分项的定价分项编号)
     * @param payloadJson 事件载荷(JSON)
     * @return 新建或已存在的事件
     */
    CcrOutboxEvent publish(String eventType, String bizKey, String payloadJson);
}
