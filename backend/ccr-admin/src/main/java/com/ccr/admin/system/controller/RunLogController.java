package com.ccr.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.hutool.core.util.StrUtil;
import com.ccr.common.core.domain.R;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行日志监控(运行报错,非审计):ccr_error_log 分页查询/详情/标记处理。
 * 权限:仅 admin(/system/** 拦截器 + @SaCheckRole 双保险)。
 */
@RestController
@RequestMapping("/system/run-log")
@SaCheckRole("admin")
@Slf4j
public class RunLogController {

    private static final List<String> VALID_STATUS = List.of("PENDING", "HANDLED", "IGNORED");

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 运行报错列表(分页;可按 关键字/级别/状态/时间范围 过滤;列表不含堆栈,详情单独取) */
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String level,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String startTime,
                                       @RequestParam(required = false) String endTime,
                                       @RequestParam(defaultValue = "1") int pageNum,
                                       @RequestParam(defaultValue = "20") int pageSize) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (StrUtil.isNotBlank(keyword)) {
            where.append(" AND (message LIKE ? OR stack_trace LIKE ? OR logger_name LIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        if (StrUtil.isNotBlank(level)) {
            where.append(" AND level = ?");
            args.add(level.toUpperCase());
        }
        if (StrUtil.isNotBlank(status)) {
            where.append(" AND handle_status = ?");
            args.add(status.toUpperCase());
        }
        if (StrUtil.isNotBlank(startTime)) {
            where.append(" AND error_time >= ?");
            args.add(startTime);
        }
        if (StrUtil.isNotBlank(endTime)) {
            where.append(" AND error_time <= ?");
            args.add(endTime);
        }
        int page = Math.max(pageNum, 1);
        int size = Math.min(Math.max(pageSize, 1), 100);
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ccr_error_log" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);
        List<Map<String, Object>> records = jdbcTemplate.queryForList(
                "SELECT id, error_time errorTime, logger_name loggerName, level, message,"
                        + " thread_name threadName, request_uri requestUri, operator_id operatorId,"
                        + " handle_status handleStatus"
                        + " FROM ccr_error_log" + where + " ORDER BY error_time DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        Map<String, Object> data = new HashMap<>();
        data.put("total", total == null ? 0L : total);
        data.put("records", records);
        return R.ok(data);
    }

    /** 运行报错详情(含完整堆栈) */
    @GetMapping("/detail")
    public R<Map<String, Object>> detail(@RequestParam Long id) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, error_time errorTime, logger_name loggerName, level, message,"
                        + " stack_trace stackTrace, thread_name threadName, request_uri requestUri,"
                        + " operator_id operatorId, handle_status handleStatus, create_time createTime"
                        + " FROM ccr_error_log WHERE id = ?", id);
        return R.ok(row);
    }

    /** 标记处理状态(仅 PENDING/HANDLED/IGNORED;由操作人点击标记,不作身份审计) */
    @PutMapping("/status")
    public R<Void> updateStatus(@RequestParam Long id, @RequestParam String status) {
        String s = status == null ? "" : status.trim().toUpperCase();
        if (!VALID_STATUS.contains(s)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "非法处理状态:" + status);
        }
        jdbcTemplate.update("UPDATE ccr_error_log SET handle_status = ? WHERE id = ?", s, id);
        return R.ok();
    }

    /** 筛选下拉数据(级别/状态/高频 logger) */
    @GetMapping("/options")
    public R<Map<String, Object>> options() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("levels", jdbcTemplate.queryForList(
                "SELECT DISTINCT level FROM ccr_error_log WHERE level IS NOT NULL ORDER BY level", String.class));
        data.put("statuses", VALID_STATUS);
        data.put("loggers", jdbcTemplate.queryForList(
                "SELECT logger_name FROM ccr_error_log WHERE logger_name IS NOT NULL"
                        + " GROUP BY logger_name ORDER BY COUNT(*) DESC LIMIT 20", String.class));
        return R.ok(data);
    }

    /** 统计:按处理状态计数(页面徽标:待处理/已处理/忽略/合计) */
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT handle_status handleStatus, COUNT(*) cnt FROM ccr_error_log"
                        + " GROUP BY handle_status");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("PENDING", 0L);
        data.put("HANDLED", 0L);
        data.put("IGNORED", 0L);
        long total = 0;
        for (Map<String, Object> row : rows) {
            Object key = row.get("handleStatus");
            long cnt = ((Number) row.get("cnt")).longValue();
            if (key != null) {
                data.put(key.toString(), cnt);
            }
            total += cnt;
        }
        data.put("total", total);
        return R.ok(data);
    }
}
