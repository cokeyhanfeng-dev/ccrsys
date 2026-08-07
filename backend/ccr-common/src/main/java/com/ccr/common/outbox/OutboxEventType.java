package com.ccr.common.outbox;

/**
 * Outbox 事件类型(§3.5)
 */
public final class OutboxEventType {

    /** 流程发起(提交事务逐分项写入,消费端发起 Warm-Flow 流程实例) */
    public static final String FLOW_START = "FLOW_START";

    /** 消息通知(消费端调 NotificationService 发送,payload 含 messageKey 幂等) */
    public static final String NOTIFY = "NOTIFY";

    /** 决议生成(分项终态写入,消费端调 ResolutionService.createResolution,已幂等) */
    public static final String RESOLUTION_CREATE = "RESOLUTION_CREATE";

    /** 承诺计划生成(决议生成后链式写入,消费端调 CommitmentService.createPlan) */
    public static final String COMMITMENT_CREATE = "COMMITMENT_CREATE";

    private OutboxEventType() {
    }
}
