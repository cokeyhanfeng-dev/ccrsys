package com.ccr.application.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 利率定价分项(ccr_pricing_item)——审批原子单位
 * 状态: DRAFT/ROUTING/LEVEL_APPROVAL/VOTING/PRESIDENT_DECISION/APPROVED/REJECTED/VETOED/SUPERSEDED/CLOSED
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_pricing_item")
public class CcrPricingItem extends BaseEntity {

    /** 所属申请 */
    private Long applicationId;

    /** 定价分项编号(唯一) */
    private String pricingItemNo;

    /** 实际定价客户号(集团场景为成员) */
    private String pricingCustomerNo;

    /** 集团成员客户号(集团场景必填) */
    private String memberCustomerNo;

    /** LOAN_CONTRACT / DEPOSIT_ACCOUNT */
    private String pricingCarrierType;

    /** 用信或担保分项来源主键 */
    private String creditTrancheRef;

    /** 产品编码 */
    private String productCode;

    /** 冻结担保组合 */
    private Long guaranteePackageId;

    /** 期限数值 */
    private Integer termValue;

    /** 日/月/年 */
    private String termUnit;

    /** 分项定价金额(万元) */
    private BigDecimal pricingAmount;

    /** 币种 */
    private String currency;

    /** 原执行利率(新增业务可为空) */
    private BigDecimal originalRate;

    /** 客户经理申请利率 */
    private BigDecimal requestedRate;

    /** 当前审批利率(随调价更新) */
    private BigDecimal currentApprovalRate;

    /** 最终决议利率 */
    private BigDecimal finalRate;

    /** LOWER_BETTER / HIGHER_BETTER */
    private String rateDirection;

    /** 计算得到路由编码 */
    private String routeCode;

    /** 完整审批链路(首节点至终审岗位,提交路由后冻结,§8.6;JSON数组,可跳过无权限节点如GM) */
    private String routeChain;

    /** 工作流实例标识 */
    private String flowInstanceId;

    /** 当前节点编码 */
    private String currentNodeCode;

    /** 审批链首节点编码(提交路由后冻结,§8.6;贷款/存款恒为 BRANCH_MANAGER) */
    private String startNodeCode;

    /** 终审节点边界利率(矩阵∩产品硬边界交集,提交路由后冻结,§8.6) */
    private BigDecimal boundaryRate;

    /** 命中的权限矩阵行编号(提交路由后冻结,审计溯源,§8.6) */
    private String matchedMatrixNo;

    /** 最终否决或一票否决原因 */
    private String finalReason;

    /** 关联重提来源分项(§7.6) */
    private Long sourcePricingItemId;

    /** 是否沿用原决议 Y/N(D18b:已批准分项连同最终利率与快照保留,不重新审批) */
    private String inheritFlag;

    /** 部门归属编码(矩阵透出并提交冻结,§D16a 部门分流:GSB/SXSB/LSB;节点处理人按此解析) */
    private String deptCode;
}
