package com.ccr.commitment.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.ccr.common.core.domain.R;
import com.ccr.commitment.domain.CcrTrackingPolicy;
import com.ccr.commitment.domain.CcrTrackingPolicyVersion;
import com.ccr.commitment.domain.CcrTrackingThreshold;
import com.ccr.commitment.service.TrackingPolicyService;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 策略请求 DTO(策略+版本+阈值一并提交) */
@Data
class PolicyReq {
    private CcrTrackingPolicy policy;
    private CcrTrackingPolicyVersion version;
    private List<CcrTrackingThreshold> thresholds;
}

/**
 * 跟踪策略配置接口(§11.5/§11.7)
 */
@RestController
@RequestMapping("/ccr/commitments/policies")
public class TrackingPolicyController {

    @Resource
    private TrackingPolicyService trackingPolicyService;

    /** 新建策略(含首个版本与阈值) */
    @SaCheckRole("admin")
    @PostMapping
    public R<CcrTrackingPolicy> create(@RequestBody PolicyReq req) {
        return R.ok(trackingPolicyService.createPolicy(req.getPolicy(), req.getVersion(), req.getThresholds()));
    }

    /** 追加版本 */
    @SaCheckRole("admin")
    @PostMapping("/{policyId}/versions")
    public R<CcrTrackingPolicyVersion> createVersion(@PathVariable Long policyId, @RequestBody PolicyReq req) {
        return R.ok(trackingPolicyService.createVersion(policyId, req.getVersion(), req.getThresholds()));
    }

    /** 策略状态变迁(DRAFT/REVIEW/EFFECTIVE/INVALID) */
    @SaCheckRole(value = {"admin", "config_reviewer"}, mode = SaMode.OR)
    @PostMapping("/{policyId}/status")
    public R<CcrTrackingPolicy> changePolicyStatus(@PathVariable Long policyId, @RequestParam String status) {
        requireStatusOperator(status);
        return R.ok(trackingPolicyService.changePolicyStatus(policyId, status));
    }

    /** 版本状态变迁(置 EFFECTIVE 校验生效区间不重叠) */
    @SaCheckRole(value = {"admin", "config_reviewer"}, mode = SaMode.OR)
    @PostMapping("/versions/{versionId}/status")
    public R<CcrTrackingPolicyVersion> changeVersionStatus(@PathVariable Long versionId, @RequestParam String status) {
        requireStatusOperator(status);
        return R.ok(trackingPolicyService.changeVersionStatus(versionId, status));
    }

    /** 策略列表 */
    @SaCheckRole(value = {"admin", "config_reviewer"}, mode = SaMode.OR)
    @GetMapping
    public R<List<CcrTrackingPolicy>> list(@RequestParam(required = false) String metricCode) {
        return R.ok(trackingPolicyService.listPolicies(metricCode));
    }

    /** 版本列表 */
    @SaCheckRole(value = {"admin", "config_reviewer"}, mode = SaMode.OR)
    @GetMapping("/{policyId}/versions")
    public R<List<CcrTrackingPolicyVersion>> versions(@PathVariable Long policyId) {
        return R.ok(trackingPolicyService.listVersions(policyId));
    }

    /** 阈值列表 */
    @SaCheckRole(value = {"admin", "config_reviewer"}, mode = SaMode.OR)
    @GetMapping("/versions/{versionId}/thresholds")
    public R<List<CcrTrackingThreshold>> thresholds(@PathVariable Long versionId) {
        return R.ok(trackingPolicyService.listThresholds(versionId));
    }

    /** 策略试算(§11.7):传入历史计划,返回命中策略与预警判定 */
    @SaCheckRole(value = {"admin", "config_reviewer"}, mode = SaMode.OR)
    @GetMapping("/simulate")
    public R<Map<String, Object>> simulate(@RequestParam Long planId) {
        return R.ok(trackingPolicyService.simulate(planId));
    }

    /** 送审与停用属于维护动作；发布、驳回由复核角色执行，admin 保留全权限。 */
    private void requireStatusOperator(String targetStatus) {
        if ("REVIEW".equals(targetStatus) || "INVALID".equals(targetStatus)) {
            StpUtil.checkRole("admin");
        }
    }
}
