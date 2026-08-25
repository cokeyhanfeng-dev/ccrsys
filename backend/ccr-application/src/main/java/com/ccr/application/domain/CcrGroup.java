package com.ccr.application.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 手工集团主数据(ccr_group)——数仓未统计的集团,申请时系统级新增;
 * 无数仓授信快照,批复总额度 approved_total_amount 手工补录(路由定档/额度勾稽基准)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_group")
public class CcrGroup extends BaseEntity {

    /** 手工集团客户编号(与数仓集团不重复) */
    private String groupNo;

    /** 集团名称 */
    private String groupName;

    /** 集团类型(默认 INDUSTRY_GROUP) */
    private String groupType;

    /** 管理行(机构) */
    private Long managerOrgId;

    /** 集团状态(默认 NORMAL) */
    private String groupStatus;

    /** 国企集团属性Y/N(集团本身属性,非旗下企业;§用户要求 2026-08-25) */
    private String stateOwnedFlag;

    /** 批复总额度(万元,手工补录,路由定档/额度勾稽基准) */
    private BigDecimal approvedTotalAmount;

    /** ISO 币种编码(默认 CNY) */
    private String currency;

    /** 备注 */
    private String remark;
}
