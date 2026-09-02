package com.ccr.approval.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * §2026-09-02 节点进入自动回填客户号的结果(供前端判断是否命中并刷新详情)
 * 适用=单户自动回填通道;backfilled=本次是否实际把占位号替换为真实号;customerNo=回填后的真实客户号(未命中为 null)。
 */
@Getter
@Setter
public class AutoBackfillResult {

    /** 是否适用单户自动回填通道(集团/无证件号场景为 false,不触发数仓反查) */
    private boolean applicable;

    /** 本次是否实际回填(数仓命中并整单占位→真实) */
    private boolean backfilled;

    /** 回填后的真实客户号;未命中/不适用为 null */
    private String customerNo;

    public AutoBackfillResult() {
    }

    public AutoBackfillResult(boolean applicable, boolean backfilled, String customerNo) {
        this.applicable = applicable;
        this.backfilled = backfilled;
        this.customerNo = customerNo;
    }
}
