package com.ccr.resolution.controller;

import com.ccr.common.core.domain.R;
import com.ccr.resolution.domain.CcrResolution;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * 决议与执行核验接口(§13.2)
 */
@RestController
@RequestMapping("/ccr/resolutions")
public class ResolutionController {

    @Resource
    private ResolutionService resolutionService;

    /** 生成决议 */
    @PostMapping
    public R<CcrResolution> create(@RequestBody Map<String, Object> body) {
        CcrResolution r = resolutionService.createResolution(
                Long.valueOf(body.get("pricingItemId").toString()),
                new BigDecimal(body.get("finalRate").toString()),
                body.get("carrierType") == null ? "LOAN_CONTRACT" : body.get("carrierType").toString(),
                body.get("carrierBusinessKey") == null ? null : body.get("carrierBusinessKey").toString(),
                body.get("effectiveFrom") == null ? null : LocalDate.parse(body.get("effectiveFrom").toString()),
                body.get("effectiveTo") == null ? null : LocalDate.parse(body.get("effectiveTo").toString()),
                body.get("decisionSource") == null ? "PRESIDENT_APPROVED" : body.get("decisionSource").toString());
        return R.ok(r);
    }

    /** 回填正式合同并校验一致性(§7.7 七项) */
    @PostMapping("/{resolutionId}/contract-bind")
    public R<CcrResolutionExecution> bind(@PathVariable Long resolutionId, @RequestBody ContractBindDTO bindDTO) {
        return R.ok(resolutionService.bindContract(resolutionId, bindDTO));
    }

    /** 执行核验(两级) */
    @GetMapping("/{resolutionId}/execution-check")
    public R<CcrResolutionExecution> check(@PathVariable Long resolutionId) {
        return R.ok(resolutionService.executeCheck(resolutionId));
    }
}
