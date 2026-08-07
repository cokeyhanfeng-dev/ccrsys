package com.ccr.common.core.domain;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一返回结果(§13.4)
 */
@Data
public class R<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态码:200 成功,500 失败,业务错误码见枚举 */
    private int code;
    /** 提示信息 */
    private String msg;
    /** 数据 */
    private T data;

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.setCode(200);
        r.setMsg("操作成功");
        r.setData(data);
        return r;
    }

    public static <T> R<T> fail(String msg) {
        return fail(500, msg);
    }

    public static <T> R<T> fail(int code, String msg) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMsg(msg);
        return r;
    }
}
