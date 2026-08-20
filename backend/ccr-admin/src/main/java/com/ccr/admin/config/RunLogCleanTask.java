package com.ccr.admin.config;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 运行报错采集保留策略:按 ccr.run-log.retention-days 清理超期 ccr_error_log(默认保留 30 天)。
 */
@Slf4j
@Component
public class RunLogCleanTask {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Value("${ccr.run-log.retention-days:30}")
    private int retentionDays;

    @Scheduled(cron = "${ccr.run-log.clean-cron:0 22 3 * * ?}")
    public void cleanExpired() {
        try {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM ccr_error_log WHERE error_time < DATE_SUB(NOW(), INTERVAL ? DAY)",
                    Math.max(retentionDays, 1));
            if (deleted > 0) {
                log.info("[run-log] 清理 {} 天前运行报错 {} 条", Math.max(retentionDays, 1), deleted);
            }
        } catch (Exception e) {
            log.warn("[run-log] 运行报错清理失败: {}", e.getMessage());
        }
    }
}
