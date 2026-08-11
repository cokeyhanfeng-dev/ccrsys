package com.ccr.rule.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * LPR 明细(§8A.3/§10.3.17)——按指标×产品逐行,不单行展示
 * product_type 对应产品编码(ccr_product.product_code,权威来源 §8A.5①);
 * 同一版本内 (lpr_term, product_type) 唯一;
 * 路由 BP 换算与产品边界校验按 (lpr_term, product_type) 精确取值,无明细时回退版本头表 1Y/5Y。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_lpr_config")
public class CcrLprConfig extends BaseEntity {

    /** 关联 LPR 版本主键(ccr_lpr_version.id) */
    private Long versionId;

    /** 指标/期限:1Y / 5Y+ */
    private String lprTerm;

    /** 产品类型(对应产品编码 ccr_product.product_code) */
    private String productType;

    /** LPR 值(%百分数,0.5–8,0.05 整数倍) */
    private BigDecimal lprValue;

    /** 相对 LPR 加点 BP(可空,按产品差异化阈值) */
    private BigDecimal lprBp;

    /** 备注 */
    private String remark;
}
