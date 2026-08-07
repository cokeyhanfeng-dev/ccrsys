package com.ccr.rule.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * LPR 版本(ccr_lpr_version)——双人复核发布,生效后不可原位修改
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_lpr_version")
public class CcrLprVersion extends BaseEntity {

    /** 版本号(唯一) */
    private String versionCode;

    /** 一年期 LPR(%) */
    @TableField("lpr_1y")
    private BigDecimal lpr1y;

    /** 五年期以上 LPR(%) */
    @TableField("lpr_5y")
    private BigDecimal lpr5y;

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
