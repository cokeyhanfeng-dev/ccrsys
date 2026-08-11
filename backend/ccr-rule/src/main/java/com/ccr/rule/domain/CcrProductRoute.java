package com.ccr.rule.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 产品审批链路配置(§8A.5②/§10.3.23)——产品↔审批链路
 * 路由引擎读取本表替代硬编码:route_mode(CHAINED/DIRECT_VOTE)、mandatory_vote(强制上会)、
 * president_decision(必经行长决策)、vote_condition(上会条件 JSON)。
 * 支行行长节点恒必经(B13),本表不含支行行长开关。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_product_route")
public class CcrProductRoute extends BaseEntity {

    /** 关联产品目录(启用产品) */
    private String productCode;

    /** 业务大类:LOAN / DEPOSIT(冗余,用于过滤) */
    private String businessBigType;

    /** Warm-Flow 流程定义 key(空=默认流程) */
    private String flowKey;

    /** 起始节点(route_mode=DIRECT_VOTE 时置空) */
    private String startNodeCode;

    /** 路由模式:CHAINED 链式逐级 / DIRECT_VOTE 直接上会(D16b 存款/保证金) */
    private String routeMode;

    /** 是否强制六人小组(Y/N,如对公贷款 >5000 万) */
    private String mandatoryVote;

    /** 是否必经总行行长决策(Y/N) */
    private String presidentDecision;

    /** 上会条件 JSON(amount_tier/enterprise_type/利率越界等,命中即上会) */
    private String voteCondition;

    /** 同一产品多配置时排序(低值优先) */
    private Integer priority;

    /** 生效日(同产品仅一版生效) */
    private LocalDateTime effectiveDate;

    /** 发布人(双人复核) */
    private Long publishBy;

    /** 复核人 */
    private Long reviewBy;

    /** 发布时间 */
    private LocalDateTime publishTime;

    /** 备注 */
    private String remark;
}
