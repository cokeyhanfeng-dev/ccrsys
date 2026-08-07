package com.ccr.application.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提交响应(§7.1 步骤7-11;幂等:重复提交返回既有结果)
 */
@Data
public class SubmitResponse {

    /** 申请主键 */
    private Long applicationId;

    /** 申请号 */
    private String applicationNo;

    /** 提交后申请状态(ROUTING) */
    private String status;

    /** 绑定的不可变快照包 */
    private Long snapshotBundleId;

    /** 冻结 LPR 版本 */
    private Long lprVersionId;

    /** 冻结利率规则集版本 */
    private Long ruleSetVersionId;

    /** 冻结路由生效日期 */
    private LocalDateTime routeAsOfDate;

    /** 提交时间 */
    private LocalDateTime submitTime;

    /** 是否本次新提交(false=幂等返回既有结果) */
    private Boolean submitted;

    /** 逐分项路由结果 */
    private List<ItemRoute> items;

    /**
     * 单分项提交后路由
     */
    @Data
    public static class ItemRoute {

        /** 定价分项主键 */
        private Long pricingItemId;

        /** 定价分项编号 */
        private String pricingItemNo;

        /** 分项状态(ROUTING) */
        private String status;

        /** 当前节点编码(BRANCH_MANAGER) */
        private String currentNodeCode;

        /** 终审岗位编码 */
        private String routeCode;

        /** 完整路由链路 */
        private List<String> routeChain;
    }
}
