package com.ccr.application.controller;

import com.ccr.application.domain.CcrApplication;
import com.ccr.application.dto.ApplicationDetailResponse;
import com.ccr.application.dto.RoutePreviewResponse;
import com.ccr.application.dto.SubmitCheckResponse;
import com.ccr.application.dto.SubmitResponse;
import com.ccr.application.service.ApplicationSubmitService;
import com.ccr.application.service.CcrApplicationService;
import com.ccr.common.core.domain.R;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PC 申请接口(设计文档 V1.0 §13.1)
 */
@Validated
@RestController
@RequestMapping("/ccr/applications")
public class CcrApplicationController {

    @Resource
    private CcrApplicationService applicationService;

    @Resource
    private ApplicationSubmitService applicationSubmitService;

    /** 创建草稿 */
    @PostMapping
    public R<CcrApplication> create(@RequestBody CcrApplication request) {
        return R.ok(applicationService.createDraft(request));
    }

    /** 保存草稿(携带 versionNo 做乐观锁校验) */
    @PutMapping("/{id}")
    public R<CcrApplication> save(@PathVariable Long id, @RequestBody CcrApplication request) {
        return R.ok(applicationService.saveDraft(id, request));
    }

    /** 查询申请详情聚合(主单+成员+分项+合同/账户关系+担保组合+承诺) */
    @GetMapping("/{id}")
    public R<ApplicationDetailResponse> detail(@PathVariable Long id) {
        return R.ok(applicationService.getApplicationDetail(id));
    }

    /** 申请列表(本人申请,按机构/状态过滤) */
    @GetMapping
    public R<java.util.List<CcrApplication>> list(
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long applicantId) {
        return R.ok(applicationService.listApplications(orgId, status, applicantId));
    }

    /** 路由预览(§13.1:逐分项 routeChain/终审岗位/边界/方向/采用 LPR 版本) */
    @PostMapping("/{id}/route-preview")
    public R<RoutePreviewResponse> routePreview(@PathVariable Long id) {
        return R.ok(applicationSubmitService.routePreview(id));
    }

    /** 提交前校验(§7.1 步骤9-10:数据批次差异+质量预校验+硬边界,前端据此弹确认) */
    @PostMapping("/{id}/submit-check")
    public R<SubmitCheckResponse> submitCheck(@PathVariable Long id) {
        return R.ok(applicationSubmitService.submitCheck(id));
    }

    /** 提交(§7.1 步骤7-11;幂等:重复提交返回既有结果) */
    @PostMapping("/{id}/submit")
    public R<SubmitResponse> submit(@PathVariable Long id) {
        return R.ok(applicationSubmitService.submit(id));
    }

    /** 关联重提(§7.6:基于终态原申请创建新草稿,已批准分项沿用原决议) */
    @PostMapping("/{id}/reapply")
    public R<CcrApplication> reapply(@PathVariable Long id) {
        return R.ok(applicationSubmitService.reapply(id));
    }
}
