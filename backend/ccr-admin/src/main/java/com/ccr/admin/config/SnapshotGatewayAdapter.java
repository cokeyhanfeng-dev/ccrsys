package com.ccr.admin.config;

import com.ccr.application.dto.SnapshotBundleResult;
import com.ccr.application.dto.SnapshotRecordInput;
import com.ccr.application.dto.SnapshotRelationInput;
import com.ccr.application.service.SnapshotGateway;
import com.ccr.snapshot.domain.CcrSnapshotBundle;
import com.ccr.snapshot.domain.CcrSnapshotRecord;
import com.ccr.snapshot.domain.CcrSnapshotRelation;
import com.ccr.snapshot.service.SnapshotService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 快照端口适配器:ccr-application 提交编排 → ccr-snapshot SnapshotService
 * 存在原因:ccr-snapshot 已依赖 ccr-application(SnapshotServiceImpl 绑定申请),
 * 反向依赖会形成 Maven 循环依赖,故在启动模块做桥接(注入 ccr-snapshot 已存在的 Service)
 */
@Component
public class SnapshotGatewayAdapter implements SnapshotGateway {

    @Resource
    private SnapshotService snapshotService;

    @Override
    public Long createBundle(Long applicationId) {
        return snapshotService.createBundle(applicationId).getId();
    }

    @Override
    public Long addRecord(Long bundleId, SnapshotRecordInput input) {
        CcrSnapshotRecord record = new CcrSnapshotRecord();
        record.setDatasetCode(input.getDatasetCode());
        record.setSubjectType(input.getSubjectType());
        record.setSubjectId(input.getSubjectId());
        record.setSourceSystemCode(input.getSourceSystemCode());
        record.setSourceRecordId(input.getSourceRecordId());
        record.setSourceDataDt(input.getSourceDataDt());
        record.setCoreJson(input.getCoreJson());
        record.setExtJson(input.getExtJson());
        snapshotService.addRecord(bundleId, record);
        return record.getId();
    }

    @Override
    public void addRelations(Long bundleId, List<SnapshotRelationInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return;
        }
        List<CcrSnapshotRelation> relations = inputs.stream().map(i -> {
            CcrSnapshotRelation relation = new CcrSnapshotRelation();
            relation.setParentRecordId(i.getParentRecordId());
            relation.setChildRecordId(i.getChildRecordId());
            relation.setRelationType(i.getRelationType());
            relation.setSequenceNo(i.getSequenceNo());
            return relation;
        }).collect(Collectors.toList());
        snapshotService.addRelations(bundleId, relations);
    }

    @Override
    public String validate(Long bundleId) {
        return snapshotService.validate(bundleId);
    }

    @Override
    public SnapshotBundleResult freeze(Long bundleId) {
        CcrSnapshotBundle bundle = snapshotService.freeze(bundleId);
        SnapshotBundleResult result = new SnapshotBundleResult();
        result.setBundleId(bundle.getId());
        result.setBundleNo(bundle.getBundleNo());
        result.setStatus(bundle.getStatus());
        result.setRecordCount(bundle.getRecordCount());
        return result;
    }
}
