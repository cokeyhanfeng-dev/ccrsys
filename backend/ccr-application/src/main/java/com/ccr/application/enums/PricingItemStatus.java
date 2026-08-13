package com.ccr.application.enums;

import lombok.Getter;

/**
 * 担保分项状态(PRD V2 §7.6,逐担保类型独立 D5/D18a)
 * 每个担保类型独立路由/校验/计票:权限内→APPROVED_LEVEL→FINAL;上会→VOTING→COMMITTEE_PASS→PRESIDENT_DECISION→FINAL/VETOED
 * 被否决/被否决后保持终态;重提创建新分项,不允许原分项回到 DRAFT
 */
@Getter
public enum PricingItemStatus {

    DRAFT("DRAFT", "草稿"),
    SUBMITTED("SUBMITTED", "已提交"),
    ROUTING("ROUTING", "路由中"),
    APPROVED_LEVEL("APPROVED_LEVEL", "权限内已批"),
    VOTING("VOTING", "小组表决"),
    COMMITTEE_PASS("COMMITTEE_PASS", "小组通过"),
    PRESIDENT_DECISION("PRESIDENT_DECISION", "行长决议"),
    FINAL("FINAL", "终态"),
    VETOED("VETOED", "一票否决"),
    REJECTED("REJECTED", "否决"),
    CLOSED("CLOSED", "关闭");

    private final String code;
    private final String desc;

    PricingItemStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
