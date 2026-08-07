package com.ccr.common.outbox.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Outbox 可靠事件(ccr_outbox_event,§3.5)
 * status(继承): PENDING(待消费)/PROCESSING(消费中,乐观认领)/SUCCESS(成功)/FAILED(超 max_retry 终态)
 * event_no 唯一,生产端由 eventType+bizKey 确定性生成(重复发布幂等),消费端按 event_no 防重
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_outbox_event")
public class CcrOutboxEvent extends BaseEntity {

    /** 事件编号(唯一,消费端幂等) */
    private String eventNo;

    /** 事件类型(FLOW_START/NOTIFY/RESOLUTION_CREATE/COMMITMENT_CREATE) */
    private String eventType;

    /** 业务类型(可空) */
    private String bizType;

    /** 业务主键(可空) */
    private Long bizId;

    /** 事件载荷(JSON) */
    private String payload;

    /** 已重试次数 */
    private Integer retryCount;

    /** 最大重试次数(默认 5) */
    private Integer maxRetry;

    /** 下次重试时间(指数退避) */
    private LocalDateTime nextRetryTime;

    /** 最近错误 */
    private String lastError;
}
