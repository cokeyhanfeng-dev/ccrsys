package com.ccr.vote.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 审批业务轨迹写入视图(ccr_approval_action)——主数据归 ccr-approval 模块维护(§9.2)
 * 跨模块写入:表决模块仅在计票/行长决策流转时写入留痕行(§14.7,含 from_status/to_status),不做更新/删除
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_approval_action")
public class CcrApprovalActionTrail extends BaseEntity {

    /** 定价分项主键 */
    private Long pricingItemId;

    /** 工作流任务标识 */
    private String taskId;

    /** COUNT_PASS/COUNT_REJECT(计票) / PRESIDENT_APPROVE/VETO(行长决策) */
    private String actionType;

    /** 节点编码 */
    private String nodeCode;

    /** 操作人(系统动作计票为 0) */
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

    /** 操作时间 */
    private LocalDateTime operationTime;
}
