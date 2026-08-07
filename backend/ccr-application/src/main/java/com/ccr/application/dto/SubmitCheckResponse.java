package com.ccr.application.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 提交前校验响应(§7.1 步骤9-10:数据差异 + 质量预校验 + 硬边界,前端据此弹确认)
 */
@Data
public class SubmitCheckResponse {

    /** 申请主键 */
    private Long applicationId;

    /** 数据基线来源:DRAFT_CREATE 草稿创建 / ROUTE_PREVIEW 上次预览 / NONE 无基线 */
    private String baselineSource;

    /** 数据集批次差异清单 */
    private List<DatasetDiff> diffs;

    /** 质量预校验结果(BLOCK/WARN/PASS) */
    private List<QualityPrecheckItem> qualityPrecheck;

    /** 逐分项硬边界校验结果 */
    private List<HardBoundaryItem> hardBoundaries;

    /** 是否存在阻断项(质量 BLOCK 或硬边界突破),前端确认也不得越过的项 */
    private Boolean blockSubmit;

    /**
     * 数据集批次差异
     */
    @Data
    public static class DatasetDiff {

        /** 数据集编码(数仓表名) */
        private String datasetCode;

        /** 基线数据日期(草稿创建/上次预览) */
        private String baselineDataDt;

        /** 当前最新成功批次数据日期 */
        private String latestDataDt;

        /** 是否有新批次 */
        private Boolean changed;
    }

    /**
     * 质量预校验项
     */
    @Data
    public static class QualityPrecheckItem {

        /** 规则编码 */
        private String ruleCode;

        /** PASS/WARN/BLOCK */
        private String level;

        /** 校验对象标识 */
        private String subjectId;

        /** 结果说明 */
        private String message;
    }

    /**
     * 单分项硬边界校验结果
     */
    @Data
    public static class HardBoundaryItem {

        /** 定价分项主键 */
        private Long pricingItemId;

        /** 定价分项编号 */
        private String pricingItemNo;

        /** 产品编码 */
        private String productCode;

        /** 申请利率(%) */
        private BigDecimal requestedRate;

        /** 是否通过 */
        private Boolean pass;

        /** 硬边界利率(%) */
        private BigDecimal boundaryRate;

        /** 结果说明 */
        private String message;
    }
}
