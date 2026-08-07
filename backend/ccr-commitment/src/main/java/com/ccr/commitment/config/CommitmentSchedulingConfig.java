package com.ccr.commitment.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 承诺跟踪调度开关(§11.4)
 * 独立声明,不依赖启动类是否开启调度(@EnableScheduling 幂等,重复声明无副作用)
 */
@Configuration
@EnableScheduling
public class CommitmentSchedulingConfig {
}
