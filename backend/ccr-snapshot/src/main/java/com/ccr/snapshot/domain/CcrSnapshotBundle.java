package com.ccr.snapshot.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 快照包(ccr_snapshot_bundle)——一次申请提交的完整快照包;FROZEN 后禁止更新或删除(§A.6)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ccr_snapshot_bundle")
public class CcrSnapshotBundle extends BaseEntity {

    /** 快照包编号(唯一) */
    private String bundleNo;

    /** 所属申请 */
    private Long applicationId;

    /** 冻结时间 */
    private LocalDateTime freezeTime;

    /** 整包内容哈希 */
    private String bundleHash;

    /** 快照记录数 */
    private Integer recordCount;
}
