package com.ccr.admin.mobile;

import cn.dev33.satoken.stp.StpUtil;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 会话渠道取自服务端 TokenSession，不信任浏览器自报渠道。 */
@Component
public class MobileSessionInterceptor implements HandlerInterceptor {
    @Resource private MobileAccessService access;
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean mobilePath = path.startsWith("/mobile/");
        if (mobilePath) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
        }
        if ("/mobile/login".equals(path) || "/mobile/oa/login".equals(path)) return true;
        boolean mobileSession = StpUtil.isLogin() && MobileAccessService.CLIENT.equals(StpUtil.getTokenSession().get("client"));
        enforceChannel(mobilePath, mobileSession);
        if (mobilePath) access.requireCurrent();
        return true;
    }
    static void enforceChannel(boolean mobilePath, boolean mobileSession) {
        if (mobilePath != mobileSession) throw new ServiceException(403, "当前会话不可访问该端接口");
    }
}
