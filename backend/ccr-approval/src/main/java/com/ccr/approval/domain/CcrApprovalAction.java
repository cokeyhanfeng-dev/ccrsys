package com.ccr.approval.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 审批业务轨迹(ccr_approval_action)——通过/否决/行长同意/一票否决等业务动作(§9.2)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_approval_action")
public class CcrApprovalAction extends BaseEntity {

    /** 定价分项主键 */
    private Long pricingItemId;

    /** 工作流任务标识 */
    private String taskId;

    /** APPROVE / REJECT / PRESIDENT_APPROVE / VETO */
    private String actionType;

    /** 节点编码 */
    private String nodeCode;

    /** 操作人 */
    private Long operatorId;

    /** 操作人角色 */
    private String operatorRole;

    /** 审批意见 */
    private String actionComment;

    /** 动作前利率 */
    private BigDecimal beforeRate;

    /** 动作后利率 */
    private BigDecimal afterRate;

    /** 动作前分项状态(§14.7 流转留痕) */
    private String fromStatus;

    /** 动作后分项状态(§14.7 流转留痕) */
    private String toStatus;

    /** PC / MOBILE */
    private String operationChannel;

    /** 设备标识 */
    private String deviceId;

    /** 操作时间 */
    private LocalDateTime operationTime;

    /** 幂等键 */
    private String idempotencyKey;
}
