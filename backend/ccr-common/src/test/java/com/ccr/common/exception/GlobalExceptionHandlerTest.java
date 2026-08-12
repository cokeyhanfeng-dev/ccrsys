package com.ccr.common.exception;

import com.ccr.common.core.domain.R;
import org.junit.jupiter.api.Test;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Web 协议异常须保持明确业务码，防止客户端误判为系统故障。 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void missingParameter_returnsBadRequest() {
        R<Void> response = handler.handleMissingParameter(
                new MissingServletRequestParameterException("name", "String"));

        assertEquals(400, response.getCode());
        assertEquals("缺少必填参数:name", response.getMsg());
    }

    @Test
    void unsupportedMethod_returnsMethodNotAllowed() {
        R<Void> response = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("POST"));

        assertEquals(405, response.getCode());
        assertEquals("请求方法不受支持:POST", response.getMsg());
    }
}
