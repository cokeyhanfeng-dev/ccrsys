package com.ccr.snapshot.dto;

import com.ccr.snapshot.domain.CcrSnapshotBundle;
import com.ccr.snapshot.domain.CcrSnapshotRecord;
import lombok.Data;

import java.util.List;

/**
 * 快照包内容(§11.7 GET /ccr/snapshots/{bundleId}):包头 + 全部记录 + 关系树
 */
@Data
public class SnapshotBundleContent {

    /** 快照包头 */
    private CcrSnapshotBundle bundle;

    /** 快照记录(按主键序) */
    private List<CcrSnapshotRecord> records;

    /** 关系树(根=非任何关系子节点的记录;§A.6 集团→成员→额度→分项→合同→借据) */
    private List<RelationNode> relationTree;

    @Data
    public static class RelationNode {
        /** 快照记录主键 */
        private Long recordId;
        /** 与父节点的关系类型(根节点为 null) */
        private String relationType;
        /** 同类型顺序 */
        private Integer sequenceNo;
        /** 子节点 */
        private List<RelationNode> children;
    }
}
