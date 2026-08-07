package com.ccr.rule.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 节点权限边界(ccr_node_permission)
 * 贷款:本岗位最低可批利率(boundary_min);存款:本岗位最高可批利率(boundary_max)(§8.2)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_node_permission")
public class CcrNodePermission extends BaseEntity {

    /** 节点编码 */
    private String nodeCode;

    /** 岗位/角色编码 */
    private String roleCode;

    /** LOAN / DEPOSIT */
    private String businessType;

    /** 权限下界(贷款:本岗位最低可批) */
    private BigDecimal boundaryMinRate;

    /** 权限上界(存款:本岗位最高可批) */
    private BigDecimal boundaryMaxRate;

    /** 生效时间 */
    private LocalDateTime effectiveFrom;

    /** 失效时间 */
    private LocalDateTime effectiveTo;

    /** 发布人(双人复核) */
    private Long publishBy;

    /** 复核人 */
    private Long reviewBy;

    /** 发布时间 */
    private LocalDateTime publishTime;
}
