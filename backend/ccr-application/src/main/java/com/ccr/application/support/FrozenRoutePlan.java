package com.ccr.application.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ccr.application.domain.CcrPricingItem;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 提交冻结路由计划读取器。
 * 新提交分项只读取冻结字段推进；历史分项缺少冻结 JSON 时保留原有固定链路兼容。
 */
public final class FrozenRoutePlan {

    public static final String BRANCH_MANAGER = "BRANCH_MANAGER";
    public static final String DEPT_GENERAL_MANAGER = "DEPT_GENERAL_MANAGER";
    public static final String VICE_PRESIDENT = "VICE_PRESIDENT";
    public static final String SIX_PEOPLE_GROUP = "SIX_PEOPLE_GROUP";
    public static final String PRESIDENT = "PRESIDENT";
    public static final String ANY_BOUNDARY = "ANY";

    private FrozenRoutePlan() {
    }

    /** 节点冻结权限；frozen=false 表示历史数据需走兼容查询。 */
    public record NodePermission(boolean frozen, boolean terminalAllowed, BigDecimal boundary) {
    }

    public static List<String> executionChain(CcrPricingItem item, String businessBigType) {
        if (item != null && StrUtil.isNotBlank(item.getRouteChainJson())) {
            try {
                JSONArray array = JSONUtil.parseArray(item.getRouteChainJson());
                List<String> chain = new ArrayList<>();
                for (Object value : array) {
                    String nodeCode = String.valueOf(value);
                    if (StrUtil.isNotBlank(nodeCode) && !chain.contains(nodeCode)) {
                        chain.add(nodeCode);
                    }
                }
                if (!chain.isEmpty()) {
                    return chain;
                }
            } catch (Exception ignored) {
                // 历史脏数据继续走固定链路兼容。
            }
        }
        if (businessBigType != null && !businessBigType.startsWith("LOAN")) {
            return List.of(BRANCH_MANAGER, SIX_PEOPLE_GROUP, PRESIDENT);
        }
        return List.of(BRANCH_MANAGER, DEPT_GENERAL_MANAGER, VICE_PRESIDENT, SIX_PEOPLE_GROUP, PRESIDENT);
    }

    public static String nextNode(CcrPricingItem item, String currentNode, String businessBigType) {
        List<String> chain = executionChain(item, businessBigType);
        int current = chain.indexOf(currentNode);
        return current >= 0 && current + 1 < chain.size() ? chain.get(current + 1) : null;
    }

    public static NodePermission nodePermission(CcrPricingItem item, String nodeCode) {
        if (item == null || StrUtil.isBlank(item.getNodePermissionJson())) {
            return new NodePermission(false, false, null);
        }
        try {
            JSONObject permissions = JSONUtil.parseObj(item.getNodePermissionJson());
            if (!permissions.containsKey(nodeCode)) {
                return new NodePermission(true, false, null);
            }
            String value = permissions.getStr(nodeCode);
            if (StrUtil.isBlank(value) || ANY_BOUNDARY.equals(value)) {
                return new NodePermission(true, true, null);
            }
            return new NodePermission(true, true, new BigDecimal(value));
        } catch (Exception ignored) {
            return new NodePermission(false, false, null);
        }
    }

    public static boolean requiresPresident(CcrPricingItem item) {
        if (item != null && StrUtil.isNotBlank(item.getPresidentRequired())) {
            return "Y".equals(item.getPresidentRequired());
        }
        return executionChain(item, null).contains(PRESIDENT);
    }

    public static boolean hasFrozenPlan(CcrPricingItem item) {
        return item != null && StrUtil.isNotBlank(item.getRouteChainJson());
    }
}
