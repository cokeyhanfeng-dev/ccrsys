package com.ccr.common.enums;

import lombok.Getter;

/**
 * 错误码(设计文档 V1.0 §13.4 错误至少区分以下类别)
 */
@Getter
public enum ErrorCode {

    SUCCESS(200, "成功"),
    BAD_REQUEST(400, "参数错误"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "无权限"),
    NOT_FOUND(404, "资源不存在"),
    INTERNAL_ERROR(500, "系统繁忙"),

    DATA_EXPIRED(1001, "数据过期,请刷新后重试"),
    QUALITY_BLOCK(1002, "数据质量阻断"),
    LIMIT_INCONSISTENT(1003, "额度不一致"),
    RULE_NO_MATCH(1004, "规则无匹配"),
    RULE_MULTI_MATCH(1005, "规则多匹配"),
    HARD_BOUNDARY(1006, "突破业务硬边界"),
    NODE_PERMISSION(1007, "节点权限不足"),
    TASK_PROCESSED(1008, "任务已处理"),
    DUPLICATE_VOTE(1009, "重复投票"),
    DATA_VERSION_CONFLICT(1010, "数据版本冲突"),
    FLOW_STATUS_CONFLICT(1011, "流程状态冲突"),
    MESSAGE_SEND_FAIL(1012, "消息发送失败"),
    IDEMPOTENCY_REPEAT(1013, "重复提交"),
    LPR_NOT_EFFECTIVE(1014, "无生效的LPR版本"),
    DUPLICATE_APPLICATION(1015, "该贷款合同已有审批中的申请,请勿重复申请");

    private final int code;
    private final String msg;

    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
