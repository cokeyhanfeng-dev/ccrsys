package com.ccr.common.datascope.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据权限开关(方法级):标注后该接口的数据查询按登录人数据权限范围
 * (ALL/DEPT/SELF)在 SQL 层自动注入过滤条件(§5.4/§11.x)。
 * 未标注的接口不注入——受限全局拦截器的白名单开关。
 * <p>由 ccr-admin 的 DataScopeAspect 处理:执行前从 Sa-Token session 恢复范围写入
 * {@link com.ccr.common.datascope.DataScopeContext},finally 清理。</p>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DataScope {
}
