package com.ccr.rule.service;

import com.ccr.rule.dto.MatrixRouteInput;
import com.ccr.rule.dto.RouteResult;

/**
 * PRD V2 §7.2 权限矩阵路由引擎(LPR±BP 参数化)
 * 贷款:首节点必经支行行长;按优先级从低到高,申请利率≥岗位边界即可终审;均不满足→上会小组
 * 存款/保证金:不设部门层级(§D16b),支行行长过手后一律合批上会小组
 * 冻结(§8.4):入参可指定 lprVersionId/asOfDate,提交时冻结、在途沿用
 */
public interface RateMatrixRouter {

    /**
     * 按权限矩阵计算唯一终审岗位(逐担保类型)
     */
    RouteResult calcRoute(MatrixRouteInput input);
}
