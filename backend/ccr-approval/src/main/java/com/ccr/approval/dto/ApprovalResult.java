package com.ccr.approval.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 普通节点审批通过的结果提示(供前端审批提交成功后的下一步提示)
 * 仅承载流转去向,节点中文名由前端 nodeLabel 统一映射(避免后端重复维护节点文案)。
 */
@Getter
@Setter
public class ApprovalResult {

    /** 本申请是否已终审结束(全部分项进入终态,审批流程完结) */
    private boolean terminal;

    /** 推进后的下一节点码;终审结束为 null;未齐套停留为本节点(等整单齐套) */
    private String nextNodeCode;

    public static ApprovalResult terminal() {
        ApprovalResult r = new ApprovalResult();
        r.setTerminal(true);
        return r;
    }

    public static ApprovalResult go(String nextNodeCode) {
        ApprovalResult r = new ApprovalResult();
        r.setNextNodeCode(nextNodeCode);
        return r;
    }
}
