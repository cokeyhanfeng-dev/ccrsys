package com.ccr.application.enums;

import lombok.Getter;

/**
 * 主申请状态(PRD V2 §7.6)
 * DRAFT→SUBMITTED→ROUTING→(权限内APPROVED_LEVEL→FINAL | 上会VOTING→COMMITTEE_PASS→PRESIDENT_DECISION→FINAL/VETOED)
 * 终态(FINAL/REJECTED/VETOED/CLOSED)不可回退;退回重提只创建新申请,原申请保持原终态(否决等)供溯源(§14.1)
 */
@Getter
public enum ApplicationStatus {

    DRAFT("DRAFT", "草稿"),
    SUBMITTED("SUBMITTED", "已提交/校验快照"),
    ROUTING("ROUTING", "路由中"),
    APPROVED_LEVEL("APPROVED_LEVEL", "权限内已批"),
    VOTING("VOTING", "表决中"),
    COMMITTEE_PASS("COMMITTEE_PASS", "小组通过"),
    PRESIDENT_DECISION("PRESIDENT_DECISION", "行长决议"),
    FINAL("FINAL", "终态(已批/已否/已否决)"),
    VETOED("VETOED", "一票否决"),
    REJECTED("REJECTED", "已否决"),
    CLOSED("CLOSED", "关闭");

    private final String code;
    private final String desc;

    ApplicationStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
