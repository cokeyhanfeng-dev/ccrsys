package com.ccr.vote.service;

/**
 * 分项终态串联(§7.6/§7.7/§11.1)
 * 分项进入终态后:批准分项生成决议并按申请承诺指标创建承诺计划,随后聚合同申请主状态。
 * 决议/承诺异常不阻断主流程(记录日志+落 PENDING 通知),由消息模块重试消费。
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
}
