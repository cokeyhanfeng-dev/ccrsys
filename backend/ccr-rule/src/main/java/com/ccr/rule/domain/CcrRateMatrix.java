package com.ccr.rule.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PRD V2 §7.2 权限矩阵(LPR±BP 参数化路由)
 * 边界语义:boundary_type=RATE 直接利率;SPREAD 存量降幅(原利率-申请利率)且不低于绝对下限
 * BP 换算:边界利率 = LPR(lpr_term) ± boundary_bp * 0.01% (新增授信)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_rate_matrix")
public class CcrRateMatrix extends BaseEntity {

    /** 矩阵行编码(唯一) */
    private String matrixNo;

    /** 业务大类:LOAN_PUBLIC/LOAN_PERSONAL/DEPOSIT/MARGIN */
    private String businessBigType;

    /** NEW新增授信/EXISTING存量授信 */
    private String newOrExisting;

    /** SOE国企/NON_SOE非国企/PERSONAL个人(空=通配) */
    private String customerType;

    /** 产品编码(空=通配;存款/保证金区分:AGREEMENT_DEPOSIT协定/NOTICE_DEPOSIT通知/BILL_MARGIN银票/CREDIT_MARGIN信用证) */
    private String productCode;

    /** 金额档:LT_5000/GE_5000(空=通配) */
    private String amountTier;

    /** 期限档:1Y/3Y/5Y(空=通配) */
    private String termTier;

    /** 担保主类型(空=通配;逐担保类型路由 D18a) */
    private String guaranteeType;

    /** 终审岗位:BRANCH_MANAGER/DEPT_GENERAL_MANAGER/VICE_PRESIDENT/SIX_PEOPLE_GROUP */
    private String startNodeCode;

    /** 部门归属(D16a) */
    private String deptCode;

    /** 边界语义:RATE直接利率 / SPREAD降幅(存量) */
    private String boundaryType;

    /** 绝对值利率下限(%)(新增=该岗位最低可批;存量=绝对下限) */
    private BigDecimal boundaryMinRate;

    /** BP:新增=LPR±BP;存量=最大可降BP */
    private Integer boundaryBp;

    /** +/-:BP方向 */
    private String bpSign;

    /** BP对应的LPR期限(1Y/5Y+) */
    private String lprTerm;

    /** 匹配优先级(低值优先) */
    private Integer priority;

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

    /** 备注 */
    private String remark;
}
