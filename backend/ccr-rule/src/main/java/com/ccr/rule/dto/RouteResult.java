package com.ccr.rule.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 路由计算结果(§8.3:起始节点、候选后续节点、终审条件和计算说明)
 * startNodeCode=审批链首节点(用户拍板口径:贷款/存款均必经支行行长过手)
 * finalNodeCode=提交利率下预计终审岗位;routeChain=本次预计路径;
 * executionChain=提交后调价、上送和表决可能到达的完整执行链。
 */
@Data
public class RouteResult {

    /** 审批链首节点编码(必经;贷款/存款=BRANCH_MANAGER) */
    private String startNodeCode;

    /** 终审岗位编码(当前申请利率下的终级审批人) */
    private String finalNodeCode;

    /** 本次预计审批链:首节点→…→预计终审岗位 */
    private List<String> routeChain;

    /** 利率比较方向:LOWER_BETTER / HIGHER_BETTER */
    private String rateDirection;

    /** 命中规则编码 */
    private String matchedRuleCode;

    /** 命中规则名称 */
    private String matchedRuleName;

    /** 终审节点权限边界利率(矩阵边界与产品硬边界取交集后,D3/§8.2;提交时冻结到分项) */
    private BigDecimal boundaryRate;

    /** 产品硬边界利率(提交时冻结;后续调价不得读取新发布配置) */
    private BigDecimal hardBoundaryRate;

    /** 命中的权限矩阵行编号(审计溯源,§8.6) */
    private String matchedMatrixNo;

    /** 部门归属编码(矩阵透出:GSB公司金融部/SXSB授信评审部/LSB零售金融部;提交时冻结到分项,§D16a 部门分流) */
    private String deptCode;

    /** 本次路由采用的产品链路主键;未配置产品链路时为空 */
    private Long productRouteId;

    /** 本次路由采用的产品链路业务版本号 */
    private Integer productRouteVersion;

    /** 冻结路由模式:CHAINED / DIRECT_VOTE */
    private String routeMode;

    /** 冻结流程定义编码 */
    private String flowKey;

    /** 是否需要行长决策 Y/N */
    private String presidentRequired;

    /**
     * 完整执行链路。与 routeChain 的当前利率计算结果分开保存，
     * 供后续调价、逐级上送和表决后流转使用。
     */
    private List<String> executionChain;

    /**
     * 各普通审批节点冻结权限边界。值为利率字符串；ANY 表示该节点无边界即可终审；
     * 节点缺失表示该节点无终审权限，必须沿 executionChain 上送。
     */
    private Map<String, String> nodePermissions;

    /** 本次路由采用的 LPR 版本主键(冻结溯源用,§8.4) */
    private Long lprVersionId;

    /** 本次路由采用的 LPR 版本号 */
    private String lprVersionCode;

    /** 计算说明 */
    private String message;
}
