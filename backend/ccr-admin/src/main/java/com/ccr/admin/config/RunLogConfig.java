package com.ccr.admin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 运行日志监控配置:注册 SQL 打印拦截器(RunSqlInterceptor)。
 * Spring Boot MybatisAutoConfiguration 会自动收集所有 Interceptor Bean 注入 SqlSessionFactory,
 * 与 MybatisPlusInterceptor(InnerInterceptor 链)互不干扰。
 */
@Configuration
public class RunLogConfig {

    @Bean
    public RunSqlInterceptor runSqlInterceptor() {
        return new RunSqlInterceptor();
    }
}
