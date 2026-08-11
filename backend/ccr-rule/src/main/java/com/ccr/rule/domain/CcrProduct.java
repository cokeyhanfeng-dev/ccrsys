package com.ccr.rule.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 产品目录(§8A.5①/§10.3.22)——产品主数据
 * 申请页产品下拉/LPR明细产品类型/权限矩阵/产品边界统一以本表为权威来源;
 * 产品编码一经启用禁改,停用后新申请不可选,在途审批不受影响(D11)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_product")
public class CcrProduct extends BaseEntity {

    /** 产品编码(唯一,一经启用禁改) */
    private String productCode;

    /** 产品名称 */
    private String productName;

    /** 业务大类:LOAN / DEPOSIT */
    private String businessBigType;

    /** 产品线(对公贷款/个人经营性贷款/对公定期/协定/通知/银票保证金/信用证保证金等) */
    private String productCategory;

    /** 适用客户类型:INDIVIDUAL / CORPORATE_SINGLE / GROUP */
    private String customerType;

    /** 币种(默认 CNY) */
    private String currency;

    /** 产品默认利率下限(申请页默认值) */
    private BigDecimal defaultMinRate;

    /** 产品默认利率上限 */
    private BigDecimal defaultMaxRate;

    /** 默认最短期限(月) */
    private Integer defaultMinTermMonths;

    /** 默认最长期限(月) */
    private Integer defaultMaxTermMonths;

    /** 生效日 */
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
