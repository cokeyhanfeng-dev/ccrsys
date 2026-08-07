package com.ccr.snapshot.service;

import com.ccr.snapshot.domain.CcrSnapshotBundle;
import com.ccr.snapshot.domain.CcrSnapshotQualityResult;
import com.ccr.snapshot.domain.CcrSnapshotRecord;
import com.ccr.snapshot.domain.CcrSnapshotRelation;

import java.util.List;
import java.util.Map;

/**
 * 数据快照服务(§10.4/§10.5、§A.6)
 */
public interface SnapshotService {

    /**
     * 创建快照包(提交时,状态 FREEZING)
     */
    CcrSnapshotBundle createBundle(Long applicationId);

    /**
     * 添加快照记录(冻结外部数据核心字段);仅 FREEZING 状态可追加
     */
    void addRecord(Long bundleId, CcrSnapshotRecord record);

    /**
     * 登记快照关系(集团→成员→额度→分项→合同→借据→担保,§A.6);仅 FREEZING 状态可登记
     */
    void addRelation(Long bundleId, Long parentRecordId, Long childRecordId, String relationType, Integer sequenceNo);

    /**
     * 批量登记快照关系(记录就绪后、冻结前一次性登记)
     */
    void addRelations(Long bundleId, List<CcrSnapshotRelation> relations);

    /**
     * 数据质量校验(§10.5):返回 PASS/WARN/BLOCK;幂等,重复调用先清理旧结果;仅 FREEZING 状态可校验
     */
    String validate(Long bundleId);

    /**
     * 按 rule_level 分组查询质量校验结果(PASS/WARN/BLOCK 明细,审批详情展示)
     */
    Map<String, List<CcrSnapshotQualityResult>> qualityResults(Long bundleId);

    /**
     * 冻结快照包(仅 FREEZING 可冻结,FROZEN 后禁止更新),并绑定到申请(§7.1 提交时冻结)
     */
    CcrSnapshotBundle freeze(Long bundleId);

    /**
     * 提交链路:创建包+添加记录+校验+冻结(提交事务入口,§7.1 步骤11)
     */
    CcrSnapshotBundle submitSnapshot(Long applicationId, List<CcrSnapshotRecord> records);

    /**
     * 快照包内容(§11.7):包头 + 全部记录 + 关系树(审批/导出/决议核验一律读快照,D11/B15)
     */
    com.ccr.snapshot.dto.SnapshotBundleContent bundleContent(Long bundleId);

    /**
     * 质量预警人工确认(§9.6 差异确认):写入 confirm_status/confirm_by/confirm_time
     */
    CcrSnapshotQualityResult confirmQualityResult(Long id, Long operatorId);
}
