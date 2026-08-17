package com.ccr.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.common.core.domain.R;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.rule.domain.CcrMetricDefinition;
import com.ccr.rule.mapper.CcrMetricDefinitionMapper;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 指标字典管理(§9;仅 admin)
 * 前台配置化:数仓按 ccr_metric_definition 字典推送指标数据,新增指标在字典登记即可,
 * 前端承诺下拉/跟踪策略下拉无需再改代码(读 /ccr/metric-definitions/enabled)。
 * 指标编码一经创建不可改(防历史承诺跟踪错位);停用后新承诺/新策略不可选,历史跟踪不受影响。
 */
@RestController
@RequestMapping("/system/metric-definitions")
public class MetricDefinitionAdminController {

    @Resource
    private CcrMetricDefinitionMapper metricDefinitionMapper;

    /** 指标字典列表(可按状态/关键字过滤,含停用) */
    @SaCheckRole("admin")
    @GetMapping
    public R<List<CcrMetricDefinition>> list(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String keyword) {
        return R.ok(metricDefinitionMapper.selectList(new LambdaQueryWrapper<CcrMetricDefinition>()
                .eq(StrUtil.isNotBlank(status), CcrMetricDefinition::getStatus, status)
                .and(StrUtil.isNotBlank(keyword), w -> w
                        .like(CcrMetricDefinition::getMetricCode, keyword)
                        .or()
                        .like(CcrMetricDefinition::getMetricName, keyword))
                .orderByAsc(CcrMetricDefinition::getMetricCode)));
    }

    /** 新增指标(metric_code 唯一,创建即 ACTIVE) */
    @SaCheckRole("admin")
    @PostMapping
    public R<Long> create(@RequestBody CcrMetricDefinition def) {
        if (StrUtil.isBlank(def.getMetricCode()) || StrUtil.isBlank(def.getMetricName())
                || StrUtil.isBlank(def.getValueType())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "指标编码/指标名称/值类型必填");
        }
        CcrMetricDefinition exists = metricDefinitionMapper.selectOne(new LambdaQueryWrapper<CcrMetricDefinition>()
                .eq(CcrMetricDefinition::getMetricCode, def.getMetricCode()));
        if (exists != null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "指标编码已存在:" + def.getMetricCode());
        }
        def.setId(null);
        def.setStatus("ACTIVE");
        def.setCurrentCalcVersion(StrUtil.isBlank(def.getCurrentCalcVersion()) ? "V1.0" : def.getCurrentCalcVersion());
        metricDefinitionMapper.insert(def);
        return R.ok(def.getId());
    }

    /** 修改指标(仅名称/值类型/范围/单位/折算版本可改;编码禁改) */
    @SaCheckRole("admin")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody CcrMetricDefinition def) {
        CcrMetricDefinition old = metricDefinitionMapper.selectById(id);
        if (old == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "指标不存在");
        }
        if (StrUtil.isNotBlank(def.getMetricCode()) && !old.getMetricCode().equals(def.getMetricCode())) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "指标编码一经创建不可修改");
        }
        old.setMetricName(def.getMetricName());
        old.setValueType(def.getValueType());
        old.setMetricScope(def.getMetricScope());
        old.setUnit(def.getUnit());
        old.setCurrentCalcVersion(def.getCurrentCalcVersion());
        metricDefinitionMapper.updateById(old);
        return R.ok();
    }

    /** 启停指标(停用后新承诺/新策略不可选,历史跟踪不受影响) */
    @SaCheckRole("admin")
    @PostMapping("/{id}/status")
    public R<Void> changeStatus(@PathVariable Long id, @RequestParam String status) {
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "状态仅支持 ACTIVE/DISABLED");
        }
        CcrMetricDefinition def = metricDefinitionMapper.selectById(id);
        if (def == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND.getCode(), "指标不存在");
        }
        def.setStatus(status);
        metricDefinitionMapper.updateById(def);
        return R.ok();
    }
}
