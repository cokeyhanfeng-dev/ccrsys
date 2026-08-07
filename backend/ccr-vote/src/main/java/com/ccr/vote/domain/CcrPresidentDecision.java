package com.ccr.vote.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 行长分项决策(ccr_president_decision)——只接收表决通过分项(§7.5)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_president_decision")
public class CcrPresidentDecision extends BaseEntity {

    /** 定价分项主键(唯一) */
    private Long pricingItemId;

    /** APPROVE同意利率 / VETO一票否决 */
    private String decision;

    /** 意见(一票否决必填) */
    private String opinion;

    /** 行长用户id */
    private Long presidentUserId;

    /** 决策时间 */
    private LocalDateTime decisionTime;

    /** 业务版本号(乐观锁) */
    private Integer businessVersion;
}
