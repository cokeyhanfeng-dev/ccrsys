package com.ccr.vote.service;

import java.util.Map;

/**
 * 分项终态串联(§7.6/§7.7/§11.1)
 * 分项进入终态后:批准分项经 Outbox 事件驱动生成决议→承诺计划→通知,随后聚合同申请主状态。
 * 事件异步消费,失败按指数退避重试,超 max_retry 置 FAILED 并告警,不阻断审批主流程;
 * 仅当事件表写入本身失败时降级为同事务同步串联。
 */
public interface ItemFinalizationService {

    /**
     * 分项终态变更后的统一处理
     *
     * @param pricingItemId  定价分项
     * @param decisionSource 决议来源(LEVEL_APPROVED 权限内终审 / PRESIDENT_APPROVED 行长同意);
     *                       否决类终态(REJECTED/VETOED)传 null,仅做状态聚合
     */
    void afterItemTerminal(Long pricingItemId, String decisionSource);

    /**
     * RESOLUTION_CREATE 事件消费入口(Outbox 处理器调用;失败抛异常由消费者退避重试)
     * 决议生成(按分项幂等,IDEMPOTENCY_REPEAT 复用原决议)后链式写 COMMITMENT_CREATE 与决议签发 NOTIFY 事件
     *
     * @param payload 事件载荷(pricingItemId/finalRate/carrierType/carrierBusinessKey/effectiveFrom/effectiveTo/decisionSource)
     */
    void processResolutionCreate(Map<String, Object> payload);

    /**
     * COMMITMENT_CREATE 事件消费入口(Outbox 处理器调用;失败抛异常由消费者退避重试)
     * 按申请承诺指标创建承诺计划(按 resolution_id 幂等,已存在计划则跳过;无承诺指标的分项跳过)
     *
     * @param payload 事件载荷(pricingItemId/resolutionId)
     */
    void processCommitmentCreate(Map<String, Object> payload);
}
