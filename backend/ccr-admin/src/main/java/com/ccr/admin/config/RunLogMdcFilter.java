package com.ccr.admin.config;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 运行日志监控:请求级 MDC 上下文,把当前操作人 id 与请求路径放入 MDC,
 * 供 ErrorLogDbAppender 采集错误日志时记录 request_uri/operator_id。
 * 非请求上下文(定时任务/异步线程)无 MDC,两字段落空,不影响采集。
 */
@Component
public class RunLogMdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            try {
                Object uid = StpUtil.getLoginIdDefaultNull();
                if (uid != null) {
                    MDC.put("userId", String.valueOf(uid));
                }
            } catch (Exception ignored) {
                // 未登录/无会话:不写 userId
            }
            MDC.put("requestUri", request.getRequestURI());
            chain.doFilter(request, response);
        } finally {
            MDC.remove("userId");
            MDC.remove("requestUri");
        }
    }
}
