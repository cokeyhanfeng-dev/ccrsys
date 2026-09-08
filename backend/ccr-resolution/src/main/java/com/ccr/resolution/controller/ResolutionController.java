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
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * 决议书查询页(2026-09-08 resolution_query 专用,全量可见但仅「当前有效决议」):
     * 客户名称/客户号(兼集团号)/决议书编号 三者组合子串模糊,分页;数据权限在 service 内判定
     * (resolution_query 及全量角色可查,其余 403)。字面量 /query 优先于上方 /{resolutionId}。
     */
    @GetMapping("/query")
    public R<Map<String, Object>> query(@RequestParam(defaultValue = "1") int pageNum,
                                        @RequestParam(defaultValue = "10") int pageSize,
                                        @RequestParam(required = false) String customerName,
                                        @RequestParam(required = false) String customerNo,
                                        @RequestParam(required = false) String resolutionNo) {
        return R.ok(resolutionService.queryResolutions(pageNum, pageSize, customerName, customerNo, resolutionNo));
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
