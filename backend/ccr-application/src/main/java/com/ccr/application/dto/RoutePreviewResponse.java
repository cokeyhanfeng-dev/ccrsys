package com.ccr.application.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 路由预览响应(§13.1 POST /ccr/applications/{id}/route-preview)
 */
@Data
public class RoutePreviewResponse {

    /** 申请主键 */
    private Long applicationId;

    /** 集团综合授信批复总额度(万元,§B18 金额定档基准;非集团为空) */
    private BigDecimal groupCreditTotal;

    /** 本次预览采用的 LPR 版本主键(未冻结时取当前生效) */
    private Long lprVersionId;

    /** 本次预览采用的 LPR 版本号 */
    private String lprVersionCode;

    /** 整单审批链首节点(恒为 BRANCH_MANAGER;整单交付改造:贷款=利率最低分项,存款=原流程) */
    private String startNodeCode;

    /** 整单终审岗位编码 */
    private String finalNodeCode;

    /** 整单完整路由链路 */
    private List<String> routeChain;

    /** 整单下一步审批人姓名(routeChain 首节点解析) */
    private List<String> nextApproverNames;

    /** 整单命中的权限矩阵行编号(审计溯源) */
    private String matchedMatrixNo;

    /** 整单终审节点边界利率(审计溯源) */
    private BigDecimal boundaryRate;

    /** 逐分项路由预览(退化只读分项明细,审批按整单) */
    private List<ItemRoutePreview> items;

    /**
     * 单分项路由预览
     */
    @Data
    public static class ItemRoutePreview {

        /** 定价分项主键 */
        private Long pricingItemId;

        /** 定价分项编号 */
        private String pricingItemNo;

        /** 集团成员客户号 */
        private String memberCustomerNo;

        /** 产品编码 */
        private String productCode;

        /** 申请利率(%) */
        private BigDecimal requestedRate;

        /** 利率比较方向:LOWER_BETTER/HIGHER_BETTER */
        private String rateDirection;

        /** 审批链首节点(恒为 BRANCH_MANAGER) */
        private String startNodeCode;

        /** 终审岗位编码 */
        private String finalNodeCode;

        /** 完整路由链路 */
        private List<String> routeChain;

        /** 下一步审批人姓名(routeChain 首节点解析,§2026-08-26 客户经理提交预览显示审批人) */
        private List<String> nextApproverNames;

        /** 硬边界是否通过(未配置边界视为通过) */
        private Boolean hardBoundaryPass;

        /** 硬边界利率(%) */
        private BigDecimal hardBoundaryRate;

        /** 本分项采用的 LPR 版本主键 */
        private Long lprVersionId;

        /** 本分项采用的 LPR 版本号 */
        private String lprVersionCode;

        /** 计算说明 */
        private String message;

        /** 路由计算失败错误码(规则无匹配等,分项级返回不阻断其他分项) */
        private Integer errorCode;

        /** 路由计算失败说明 */
        private String errorMessage;
    }
}
