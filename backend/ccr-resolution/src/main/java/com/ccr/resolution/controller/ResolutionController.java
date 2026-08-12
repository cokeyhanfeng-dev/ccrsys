package com.ccr.resolution.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.ccr.common.core.domain.R;
import com.ccr.resolution.domain.CcrResolutionExecution;
import com.ccr.resolution.dto.ContractBindDTO;
import com.ccr.resolution.service.ResolutionService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 决议与执行核验接口(§13.2)
 */
@RestController
@RequestMapping("/ccr/resolutions")
public class ResolutionController {

    @Resource
    private ResolutionService resolutionService;

    /** 决议列表(角色数据权限:客户经理看本人申请,行长/admin/auditor 全量,其余审批角色看本人参与过的) */
    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(resolutionService.listResolutions());
    }

    /** 决议详情(含执行记录,数据权限同列表) */
    @GetMapping("/{resolutionId}")
    public R<Map<String, Object>> detail(@PathVariable Long resolutionId) {
        return R.ok(resolutionService.resolutionDetail(resolutionId));
    }

    /** 回填正式合同并校验一致性(§7.7 七项;绑定成功同事务自动触发两级核验) */
    @SaCheckRole(value = {"contract_operator", "president", "admin"}, mode = SaMode.OR)
    @PostMapping("/{resolutionId}/contract-bind")
    public R<CcrResolutionExecution> bind(@PathVariable Long resolutionId, @RequestBody ContractBindDTO bindDTO) {
        return R.ok(resolutionService.bindContract(resolutionId, bindDTO));
    }

    /** 执行核验(两级) */
    @SaCheckRole(value = {"contract_operator", "president", "admin"}, mode = SaMode.OR)
    @GetMapping("/{resolutionId}/execution-check")
    public R<CcrResolutionExecution> check(@PathVariable Long resolutionId) {
        return R.ok(resolutionService.executeCheck(resolutionId));
    }
}
