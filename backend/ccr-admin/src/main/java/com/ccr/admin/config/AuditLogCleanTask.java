package com.ccr.admin.config;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 审计日志保留策略(§15.2):仅保留近 90 天,每天凌晨清理超期 ccr_audit_log
 */
@Slf4j
@Component
public class AuditLogCleanTask {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Scheduled(cron = "${ccr.audit.clean-cron:0 17 3 * * ?}")
    public void cleanExpired() {
        try {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM ccr_audit_log WHERE operate_time < DATE_SUB(NOW(), INTERVAL 90 DAY)");
            if (deleted > 0) {
                log.info("[audit] 清理 90 天前审计日志 {} 条", deleted);
            }
        } catch (Exception e) {
            log.warn("[audit] 审计日志清理失败: {}", e.getMessage());
        }
    }
}
