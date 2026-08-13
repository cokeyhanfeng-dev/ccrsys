package com.ccr.vote.read;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 申请承诺指标只读视图(ccr_application_commitment)——由申请模块(03a)在提交时写入
 * 跨模块只读:审批通过后作为承诺计划指标来源,本模块禁止写操作
 */
@Data
@TableName("ccr_application_commitment")
public class ApplicationCommitmentRead {

    @TableId
    private Long id;

    /** 所属申请 */
    private Long applicationId;

    /** 定价分项 */
    private Long pricingItemId;

    /** 稳定指标编码 */
    private String metricCode;

    /** INCREMENT / TARGET_BALANCE / CUMULATIVE */
    private String targetType;

    /** 基线值(万元) */
    private BigDecimal baselineValue;

    /** 目标值(万元) */
    private BigDecimal targetValue;

    /** 单位 */
    private String unit;

    /** 指标范围:PUBLIC/PRIVATE_SELF/RELATED/GROUP/GROUP_MEMBER */
    private String metricScope;

    /** 成员客户号(成员级指标) */
    private String memberCustomerNo;

    /** 逻辑删除:0 有效/1 已删(申请版本更新时旧指标逻辑删除,查询须过滤) */
    private String delFlag;
}
