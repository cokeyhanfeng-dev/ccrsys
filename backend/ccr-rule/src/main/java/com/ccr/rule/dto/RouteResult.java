package com.ccr.rule.dto;

import lombok.Data;

import java.util.List;

/**
 * 路由计算结果(§8.3:起始节点、候选后续节点、终审条件和计算说明)
 * startNodeCode=审批链首节点(用户拍板口径:贷款/存款均必经支行行长过手)
 * finalNodeCode=终审岗位;routeChain=首节点至终审岗位的完整链路(部门总经理行保留,调价后可达终审)
 */
@Data
public class RouteResult {

    /** 审批链首节点编码(必经;贷款/存款=BRANCH_MANAGER) */
    private String startNodeCode;

    /** 终审岗位编码(当前申请利率下的终级审批人) */
    private String finalNodeCode;

    /** 审批链:首节点→…→终审岗位(含保留的中间层级) */
    private List<String> routeChain;

    /** 利率比较方向:LOWER_BETTER / HIGHER_BETTER */
    private String rateDirection;

    /** 命中规则编码 */
    private String matchedRuleCode;

    /** 命中规则名称 */
    private String matchedRuleName;

    /** 终审节点权限边界利率(矩阵边界与产品硬边界取交集后,D3/§8.2;提交时冻结到分项) */
    private java.math.BigDecimal boundaryRate;

    /** 命中的权限矩阵行编号(审计溯源,§8.6) */
    private String matchedMatrixNo;

    /** 部门归属编码(矩阵透出:3202233912公司金融部/3202233943授信评审部/3202233991零售金融;提交时冻结到分项,§D16a 部门分流) */
    private String deptCode;

    /** 本次路由采用的 LPR 版本主键(冻结溯源用,§8.4) */
    private Long lprVersionId;

    /** 本次路由采用的 LPR 版本号 */
    private String lprVersionCode;

    /** 计算说明 */
    private String message;
}
