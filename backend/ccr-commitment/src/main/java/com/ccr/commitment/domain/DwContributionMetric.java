package com.ccr.commitment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 数仓当前贡献度单指标表(dw_contribution_metric)——只读视图(D13/D21)
 * 实际达成值取数口径: cust_no + metric_code 维度最近批次;CUMULATIVE 按计划期间累计
 */
@Data
@TableName("dw_contribution_metric")
public class DwContributionMetric {

    /** ETL 自增主键 */
    @TableId(value = "etl_md5", type = IdType.AUTO)
    private Long etlMd5;

    /** 数据日期 */
    private LocalDate dataDt;

    /** 客户号 */
    private String custNo;

    /** 指标编码 */
    private String metricCode;

    /** 指标名 */
    private String metricName;

    /** 指标值(万元) */
    private BigDecimal metricValue;

    /** AVG_BALANCE业务余额/INCOME收入/CONTRIBUTION_AMOUNT折算 */
    private String valueType;

    /** PUBLIC/PRIVATE_SELF/RELATED */
    private String metricScope;
}
