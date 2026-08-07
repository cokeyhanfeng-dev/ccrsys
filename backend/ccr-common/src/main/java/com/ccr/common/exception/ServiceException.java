package com.ccr.common.exception;

import lombok.Getter;

import java.io.Serial;

/**
 * 业务异常(统一由 GlobalExceptionHandler 捕获)
 */
@Getter
public class ServiceException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 错误码(对应 §13.4 错误分类) */
    private final int code;

    public ServiceException(String message) {
        this(500, message);
    }

    public ServiceException(int code, String message) {
        super(message);
        this.code = code;
    }
}
