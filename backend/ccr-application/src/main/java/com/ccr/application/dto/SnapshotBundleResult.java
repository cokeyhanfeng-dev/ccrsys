package com.ccr.application.dto;

import lombok.Data;

/**
 * 快照包结果(冻结后返回)
 */
@Data
public class SnapshotBundleResult {

    /** 快照包主键 */
    private Long bundleId;

    /** 快照包编号 */
    private String bundleNo;

    /** 快照包状态(FREEZING/FROZEN) */
    private String status;

    /** 快照记录数 */
    private Integer recordCount;
}
