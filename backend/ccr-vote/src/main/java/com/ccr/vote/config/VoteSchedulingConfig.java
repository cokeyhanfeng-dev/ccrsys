package com.ccr.vote.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 表决模块定时任务调度声明(超时计票扫描,§7.5.5)
 * 独立声明,不依赖启动类是否开启调度(@EnableScheduling 幂等,重复声明无副作用)
 */
@Configuration
@EnableScheduling
public class VoteSchedulingConfig {
}
