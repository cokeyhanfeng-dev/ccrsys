package com.ccr.snapshot.controller;

import com.ccr.common.core.domain.R;
import com.ccr.snapshot.service.DatacenterService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据中心监控接口(§11.7,只读;登录即可访问,登录校验由 Sa-Token 全局拦截器承担)
 */
@RestController
@RequestMapping("/ccr/datacenter")
public class DatacenterController {

    @Resource
    private DatacenterService datacenterService;

    /** 批次落地监控:各数仓表最新 data_dt、批次行数、落地时间概览 */
    @GetMapping("/batches")
    public R<List<Map<String, Object>>> batches() {
        return R.ok(datacenterService.batchOverview());
    }

    /** 数据源时效看板:最新 data_dt、距今天数、对比容忍阈值得出 OK/STALE */
    @GetMapping("/source-status")
    public R<List<Map<String, Object>>> sourceStatus() {
        return R.ok(datacenterService.sourceStatus());
    }
}
