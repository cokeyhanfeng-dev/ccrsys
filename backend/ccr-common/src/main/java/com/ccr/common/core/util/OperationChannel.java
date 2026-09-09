package com.ccr.common.core.util;

import cn.dev33.satoken.stp.StpUtil;

/** 审计渠道仅取服务端会话标记，禁止信任请求体传入的渠道。 */
public final class OperationChannel {
    private OperationChannel() {}
    public static String current() {
        var context = cn.dev33.satoken.SaManager.getSaTokenContext();
        return context != null && context.isValid() && StpUtil.isLogin() && "ccr-mobile".equals(StpUtil.getTokenSession().get("client")) ? "MOBILE" : "PC";
    }
}
