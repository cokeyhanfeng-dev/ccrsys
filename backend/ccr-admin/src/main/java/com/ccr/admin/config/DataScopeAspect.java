package com.ccr.admin.config;

import cn.dev33.satoken.stp.StpUtil;
import com.ccr.common.datascope.DataScope;
import com.ccr.common.datascope.DataScopeContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 数据权限切面(§5.4):仅对标注 {@link com.ccr.common.datascope.annotation.DataScope} 的
 * controller 方法生效——受限全局拦截器的开关。
 * <p>执行前从 Sa-Token session(登录时 {@code AuthController} 写入 dataScopeLevel/
 * dataScopeOrgCodePrefix)恢复范围写入 {@link DataScopeContext},finally 清理;
 * 未登录或无 session 时写入 null → {@link com.ccr.common.datascope.CcrDataPermissionHandler}
 * 不注入,已验证功能零影响。</p>
 */
@Aspect
@Component
public class DataScopeAspect {

    @Around("@annotation(com.ccr.common.datascope.annotation.DataScope)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        try {
            DataScopeContext.set(resolveFromSession());
            return pjp.proceed();
        } finally {
            DataScopeContext.clear();
        }
    }

    private DataScope resolveFromSession() {
        try {
            String level = StpUtil.getSession().getString("dataScopeLevel");
            if (level == null) {
                return null;
            }
            Long userId = StpUtil.getLoginIdAsLong();
            String orgCodePrefix = StpUtil.getSession().getString("dataScopeOrgCodePrefix");
            return new DataScope(level, orgCodePrefix, userId);
        } catch (Exception e) {
            return null;
        }
    }
}
