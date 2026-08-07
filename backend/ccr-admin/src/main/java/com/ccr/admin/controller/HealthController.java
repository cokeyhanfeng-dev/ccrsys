package com.ccr.admin.controller;

import com.ccr.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 健康检查(首期占位,验证服务与数据库连通)
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        return R.ok(Map.of(
                "service", "ccr-rate",
                "status", "UP",
                "time", LocalDateTime.now().toString()
        ));
    }
}
