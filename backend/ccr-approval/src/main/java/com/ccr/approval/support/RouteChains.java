package com.ccr.approval.support;

import cn.hutool.core.util.StrUtil;

import java.util.List;

/**
 * 流程路由链(用户拍板口径)
 * 贷款:BRANCH_MANAGER→DEPT_GENERAL_MANAGER→VICE_PRESIDENT→SIX_PEOPLE_GROUP,按 route_code(终审岗位)截断;
 * 存款/保证金:BRANCH_MANAGER→SIX_PEOPLE_GROUP(无部门层级,支行过手后直接上会)
 */
public final class RouteChains {

    public static final String BRANCH_MANAGER = "BRANCH_MANAGER";
    public static final String DEPT_GENERAL_MANAGER = "DEPT_GENERAL_MANAGER";
    public static final String VICE_PRESIDENT = "VICE_PRESIDENT";
    public static final String SIX_PEOPLE_GROUP = "SIX_PEOPLE_GROUP";

    private static final List<String> LOAN_CHAIN =
            List.of(BRANCH_MANAGER, DEPT_GENERAL_MANAGER, VICE_PRESIDENT, SIX_PEOPLE_GROUP);
    private static final List<String> DEPOSIT_CHAIN = List.of(BRANCH_MANAGER, SIX_PEOPLE_GROUP);

    private RouteChains() {
    }

    /** 全链(按业务类型) */
    public static List<String> fullChain(String businessType) {
        return "DEPOSIT".equals(businessType) ? DEPOSIT_CHAIN : LOAN_CHAIN;
    }

    /** 首节点至终审岗位(routeCode)的路由链;routeCode 为空或不在链上时返回全链 */
    public static List<String> of(String businessType, String routeCode) {
        List<String> chain = fullChain(businessType);
        if (StrUtil.isBlank(routeCode)) {
            return chain;
        }
        int idx = chain.indexOf(routeCode);
        return idx < 0 ? chain : chain.subList(0, idx + 1);
    }

    /** 贷款链下一节点(超权限上送);已在终点返回 null */
    public static String nextNode(String nodeCode) {
        return nextNode(nodeCode, null);
    }

    /**
     * 沿给定链路找下一节点(超权限上送);优先沿分项提交时冻结的完整链路
     * (矩阵驱动,可跳过无权限节点如 GM,保证推进与提交预览一致),链路为空回退贷款固定链;
     * 已在终点或不在链上返回 null。
     */
    public static String nextNode(String nodeCode, List<String> chain) {
        if (StrUtil.isBlank(nodeCode)) {
            return null;
        }
        List<String> effective = (chain == null || chain.isEmpty()) ? LOAN_CHAIN : chain;
        int idx = effective.indexOf(nodeCode);
        if (idx < 0 || idx == effective.size() - 1) {
            return null;
        }
        return effective.get(idx + 1);
    }
}
