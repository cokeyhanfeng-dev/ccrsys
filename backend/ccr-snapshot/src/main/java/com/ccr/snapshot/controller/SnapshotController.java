package com.ccr.snapshot.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.ccr.common.core.domain.R;
import com.ccr.common.enums.ErrorCode;
import com.ccr.common.exception.ServiceException;
import com.ccr.snapshot.domain.CcrSnapshotBundle;
import com.ccr.snapshot.domain.CcrSnapshotQualityResult;
import com.ccr.snapshot.domain.CcrSnapshotRecord;
import com.ccr.snapshot.domain.CcrSnapshotRelation;
import com.ccr.snapshot.dto.SnapshotBundleContent;
import com.ccr.snapshot.dto.SubmitRequest;
import com.ccr.snapshot.service.SnapshotService;
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
 * 数据快照接口(提交冻结,§7.1 步骤11)
 */
@RestController
@RequestMapping("/ccr/snapshots")
@SaCheckRole("admin")
public class SnapshotController {

    @Resource
    private SnapshotService snapshotService;

    /** 创建快照包 */
    @PostMapping("/bundles")
    public R<CcrSnapshotBundle> create(@RequestBody Map<String, Object> body) {
        return R.ok(snapshotService.createBundle(Long.valueOf(body.get("applicationId").toString())));
    }

    /** 添加快照记录 */
    @PostMapping("/{bundleId}/records")
    public R<Void> addRecord(@PathVariable Long bundleId, @RequestBody CcrSnapshotRecord record) {
        snapshotService.addRecord(bundleId, record);
        return R.ok();
    }

    /** 登记快照关系(单条) */
    @PostMapping("/{bundleId}/relations")
    public R<Void> addRelation(@PathVariable Long bundleId, @RequestBody CcrSnapshotRelation relation) {
        snapshotService.addRelation(bundleId, relation.getParentRecordId(), relation.getChildRecordId(),
                relation.getRelationType(), relation.getSequenceNo());
        return R.ok();
    }

    /** 批量登记快照关系(记录就绪后、冻结前) */
    @PostMapping("/{bundleId}/relations/batch")
    public R<Void> addRelations(@PathVariable Long bundleId, @RequestBody List<CcrSnapshotRelation> relations) {
        snapshotService.addRelations(bundleId, relations);
        return R.ok();
    }

    /** 数据质量校验 */
    @PostMapping("/{bundleId}/validate")
    public R<String> validate(@PathVariable Long bundleId) {
        return R.ok(snapshotService.validate(bundleId));
    }

    /** 质量校验结果(按 PASS/WARN/BLOCK 分组,审批详情展示) */
    @GetMapping("/{bundleId}/quality-results")
    public R<Map<String, List<CcrSnapshotQualityResult>>> qualityResults(@PathVariable Long bundleId) {
        return R.ok(snapshotService.qualityResults(bundleId));
    }

    /** 快照包内容(§11.7:包头+全部记录+关系树;审批/导出/决议核验一律读快照) */
    @GetMapping("/{bundleId}")
    public R<SnapshotBundleContent> content(@PathVariable Long bundleId) {
        return R.ok(snapshotService.bundleContent(bundleId));
    }

    /** 质量预警人工确认(§9.6 差异确认;确认人取 Sa-Token 登录人,不接受传参) */
    @PostMapping("/quality/{id}/confirm")
    public R<CcrSnapshotQualityResult> confirmQuality(@PathVariable Long id) {
        return R.ok(snapshotService.confirmQualityResult(id, StpUtil.getLoginIdAsLong()));
    }

    /** 冻结并绑定申请 */
    @PostMapping("/{bundleId}/freeze")
    public R<CcrSnapshotBundle> freeze(@PathVariable Long bundleId) {
        return R.ok(snapshotService.freeze(bundleId));
    }

    /** 提交快照(创建+记录+校验+冻结,演示完整链路) */
    @PostMapping("/submit")
    public R<CcrSnapshotBundle> submit(@RequestBody SubmitRequest request) {
        if (request.getApplicationId() == null) {
            throw new ServiceException(ErrorCode.BAD_REQUEST.getCode(), "applicationId 不能为空");
        }
        return R.ok(snapshotService.submitSnapshot(request.getApplicationId(), request.getRecords()));
    }
}
