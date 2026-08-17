package com.ccr.admin.system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.common.core.domain.R;
import com.ccr.rule.domain.CcrMetricDefinition;
import com.ccr.rule.mapper.CcrMetricDefinitionMapper;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 指标字典公开只读接口(§9)
 * 承诺指标下拉/跟踪策略指标过滤以 ccr_metric_definition 为权威来源,
 * 但 /system/** 仅 admin 可访问,客户经理/行长无法读取;
 * 故在 /ccr/** 下暴露"启用指标"只读端点(登录即可,任意角色),替代前端硬编码 METRIC_CODES。
 * 数仓新增指标在字典登记即可,前端下拉无需再改代码。
 */
@RestController
@RequestMapping("/ccr/metric-definitions")
public class MetricDefinitionController {

    @Resource
    private CcrMetricDefinitionMapper metricDefinitionMapper;

    /** 启用指标字典(承诺下拉/跟踪策略指标的权威来源) */
    @GetMapping("/enabled")
    public R<List<CcrMetricDefinition>> enabled() {
        return R.ok(metricDefinitionMapper.selectList(new LambdaQueryWrapper<CcrMetricDefinition>()
                .eq(CcrMetricDefinition::getStatus, "ACTIVE")
                .orderByAsc(CcrMetricDefinition::getMetricCode)));
    }
}
