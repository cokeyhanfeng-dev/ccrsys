package com.ccr.admin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 客户贡献度与利率决策系统 · 启动入口
 * 技术栈: Spring Boot 3.x / JDK 17 / MyBatis-Plus / Sa-Token / MySQL 8
 */
@SpringBootApplication(scanBasePackages = "com.ccr")
@MapperScan("com.ccr.**.mapper")
@EnableScheduling
public class CcrApplication {

    public static void main(String[] args) {
        SpringApplication.run(CcrApplication.class, args);
    }
}
