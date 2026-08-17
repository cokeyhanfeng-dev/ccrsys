package com.ccr.rule.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 贡献度指标定义(§9)——承诺/跟踪指标编号的权威字典
 * 指标编号收敛:前端承诺指标下拉、跟踪策略指标过滤/展示统一以本表为来源;
 * 数仓 dw_contribution_metric 推指标时在字典登记 metric_code 即可,前端不再硬编码。
 * metric_code 全局唯一(uk_metric_code);value_type: AVG_BALANCE/INCOME/CONTRIBUTION_AMOUNT/RATIO。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_metric_definition")
public class CcrMetricDefinition extends BaseEntity {

    /** 稳定指标编码(唯一,如 TOTAL/GM_LOAN_CONTRIBUTION/PUBLIC_DEPOSIT_AVG) */
    private String metricCode;

    /** 指标名称 */
    private String metricName;

    /** 值类型:AVG_BALANCE 业务余额 / INCOME 收入 / CONTRIBUTION_AMOUNT 折算 / RATIO 派生比值 */
    private String valueType;

    /** 适用范围:PUBLIC / PRIVATE_SELF / RELATED / GROUP / GROUP_MEMBER */
    private String metricScope;

    /** 单位(万元/户/%) */
    private String unit;

    /** 当前折算版本(默认 V1.0) */
    private String currentCalcVersion;
}
