package com.ccr.snapshot.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.ccr.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.util.Map;

/**
 * 快照记录通用头(ccr_snapshot_record)——稳定核心字段+ext_json(§A.6)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "ccr_snapshot_record", autoResultMap = true)
public class CcrSnapshotRecord extends BaseEntity {

    /** 快照包主键 */
    private Long bundleId;

    /** 数据集编码 */
    private String datasetCode;

    /** 数据集定义版本 */
    private Long datasetVersionId;

    /** 个人/企业/集团/成员/额度/分项/合同/借据/存款账户/担保等 */
    private String subjectType;

    /** 标准主体标识 */
    private String subjectId;

    /** 来源系统 */
    private String sourceSystemCode;

    /** 来源主键 */
    private String sourceRecordId;

    /** 来源成功批次日期 */
    private LocalDate sourceDataDt;

    /** 内容哈希 */
    private String payloadHash;

    /** 审批必须还原的标准字段 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> coreJson;

    /** 按版本解析的扩展属性 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> extJson;
}
