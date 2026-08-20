package com.ccr.admin.config;

import jakarta.annotation.Resource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 运行日志监控:Spring 容器就绪后把 JdbcTemplate 注入 ERROR_DB Appender
 * (logback 初始化早于 Spring 容器,Appender 需延迟获取数据库连接)。
 */
@Component
public class RunLogDbBootstrap implements InitializingBean {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public void afterPropertiesSet() {
        ErrorLogDbAppender.setJdbcTemplate(jdbcTemplate);
    }
}
