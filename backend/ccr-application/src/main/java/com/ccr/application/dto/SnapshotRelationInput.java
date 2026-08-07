package com.ccr.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 快照关系登记输入(§A.6 有向关系,桥接到 SnapshotService.addRelations)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SnapshotRelationInput {

    /** 父快照记录主键 */
    private Long parentRecordId;

    /** 子快照记录主键 */
    private Long childRecordId;

    /** 关系类型:GROUP_TO_MEMBER/MEMBER_TO_LIMIT/LIMIT_TO_TRANCHE/TRANCHE_TO_CONTRACT/CONTRACT_TO_NOTE */
    private String relationType;

    /** 同类型顺序 */
    private Integer sequenceNo;
}
