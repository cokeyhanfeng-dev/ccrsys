package com.ccr.snapshot.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 快照关系(ccr_snapshot_relation)——有向关系(§A.6)
 * GROUP_TO_MEMBER / MEMBER_TO_LIMIT / LIMIT_TO_TRANCHE / TRANCHE_TO_CONTRACT / CONTRACT_TO_NOTE / TO_GUARANTEE
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_snapshot_relation")
public class CcrSnapshotRelation extends BaseEntity {

    /** 快照包主键 */
    private Long bundleId;

    /** 父快照记录主键 */
    private Long parentRecordId;

    /** 子快照记录主键 */
    private Long childRecordId;

    /** 关系类型 */
    private String relationType;

    /** 同类型顺序 */
    private Integer sequenceNo;
}
