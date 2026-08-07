package com.ccr.application.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.Map;

/**
 * 快照记录采集输入(本模块自有快照端口 DTO,避免与 ccr-snapshot 产生 Maven 循环依赖;
 * 由启动模块适配器桥接到 SnapshotService.addRecord)
 */
@Data
public class SnapshotRecordInput {

    /** 数据集编码(数仓表名) */
    private String datasetCode;

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

    /** 审批必须还原的标准字段 */
    private Map<String, Object> coreJson;

    /** 扩展属性 */
    private Map<String, Object> extJson;
}
