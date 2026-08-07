package com.ccr.snapshot.dto;

import com.ccr.snapshot.domain.CcrSnapshotRecord;
import lombok.Data;

import java.util.List;

/**
 * 快照提交请求(创建包+记录+校验+冻结,§7.1 步骤11)
 */
@Data
public class SubmitRequest {

    /** 所属申请 */
    private Long applicationId;

    /** 快照记录列表 */
    private List<CcrSnapshotRecord> records;
}
