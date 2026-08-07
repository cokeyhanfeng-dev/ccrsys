package com.ccr.admin.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 拦截器配置(§4.1 接入与权限层)
 * 登录校验;白名单放行健康检查、登录与演示接口;
 * /system/** 基础系统功能仅系统管理员(admin)角色可用;
 * /system/flow/thresholds/** 阈值配置(参数管理)放行 admin/config_reviewer 任一角色
 * (用户拍板:取消 param_admin 角色,参数维护归 admin、复核发布归 config_reviewer)
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> {
                    SaRouter.match("/**")
                            .notMatch("/actuator/**", "/health", "/auth/login", "/demo/**")
                            .check(r -> StpUtil.checkLogin());
                    // 阈值配置(参数管理):admin/config_reviewer 任一角色可用
                    SaRouter.match("/system/flow/thresholds/**")
                            .check(r -> StpUtil.checkRoleOr("admin", "config_reviewer"));
                    // 其余系统管理接口(用户/角色/部门/流程定义)仅 admin 角色
                    SaRouter.match("/system/**")
                            .notMatch("/system/flow/thresholds/**")
                            .check(r -> StpUtil.checkRole("admin"));
                }))
                .addPathPatterns("/**");
    }
}
