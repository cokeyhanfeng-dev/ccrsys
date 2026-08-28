package com.ccr.common.exception;

import com.ccr.common.core.domain.R;
import com.ccr.common.enums.ErrorCode;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * 全局异常处理(§13.4 统一返回和错误码)
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常 */
    @ExceptionHandler(ServiceException.class)
    public R<Void> handleServiceException(ServiceException e, HttpServletRequest request) {
        log.warn("业务异常 path={}, code={}, msg={}", request.getRequestURI(), e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    /** Sa-Token 未登录 */
    @ExceptionHandler(NotLoginException.class)
    public R<Void> handleNotLogin(NotLoginException e, HttpServletRequest request) {
        log.warn("未登录访问 path={}", request.getRequestURI());
        return R.fail(ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getMsg());
    }

    /** Sa-Token 权限码不足 */
    @ExceptionHandler(NotPermissionException.class)
    public R<Void> handleNotPermission(NotPermissionException e, HttpServletRequest request) {
        log.warn("权限不足 path={}, permission={}", request.getRequestURI(), e.getPermission());
        return R.fail(ErrorCode.FORBIDDEN.getCode(), ErrorCode.FORBIDDEN.getMsg());
    }

    /** Sa-Token 角色不足 */
    @ExceptionHandler(NotRoleException.class)
    public R<Void> handleNotRole(NotRoleException e, HttpServletRequest request) {
        log.warn("角色不足 path={}, role={}", request.getRequestURI(), e.getRole());
        return R.fail(ErrorCode.FORBIDDEN.getCode(), ErrorCode.FORBIDDEN.getMsg());
    }

    /** 数据库唯一键冲突(重复提交/并发写入) */
    @ExceptionHandler(DuplicateKeyException.class)
    public R<Void> handleDuplicateKey(DuplicateKeyException e, HttpServletRequest request) {
        log.warn("唯一键冲突 path={}, msg={}", request.getRequestURI(), e.getMessage());
        return R.fail(ErrorCode.IDEMPOTENCY_REPEAT.getCode(), ErrorCode.IDEMPOTENCY_REPEAT.getMsg());
    }

    /** 参数校验异常(@Valid) */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValidException(MethodArgumentNotValidException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        String msg = fe == null ? "参数校验失败" : fe.getDefaultMessage();
        return R.fail(400, msg);
    }

    /** 表单绑定异常 */
    @ExceptionHandler(BindException.class)
    public R<Void> handleBindException(BindException e) {
        FieldError fe = e.getBindingResult().getFieldError();
        String msg = fe == null ? "参数绑定失败" : fe.getDefaultMessage();
        return R.fail(400, msg);
    }

    /** 缺少必填查询参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public R<Void> handleMissingParameter(MissingServletRequestParameterException e) {
        return R.fail(ErrorCode.BAD_REQUEST.getCode(), "缺少必填参数:" + e.getParameterName());
    }

    /** 路径存在但 HTTP 方法不受支持 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public R<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return R.fail(405, "请求方法不受支持:" + e.getMethod());
    }

    /** 未找到处理器 */
    @ExceptionHandler(NoHandlerFoundException.class)
    public R<Void> handleNoHandler(NoHandlerFoundException e) {
        return R.fail(404, "接口不存在");
    }

    /**
     * 客户端提前断开连接(AsyncRequestNotUsable):用户刷新/跳转/关闭页面,响应已无法写出。
     * 非业务错误——若落兜底 Exception 会打 ERROR 污染生产运行监控(2026-08-27 生产反馈 Broken pipe)。
     * 返回 void 不再尝试写响应,仅降级 DEBUG 一句话。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException e) {
        log.debug("客户端中断响应(AsyncRequestNotUsable),忽略: {}", e.getMessage());
    }

    /** Tomcat 层客户端中断(同步响应写流 Broken pipe),同上 */
    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbort(ClientAbortException e) {
        log.debug("客户端中断响应(ClientAbort),忽略: {}", e.getMessage());
    }

    /** 兜底异常 */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e, HttpServletRequest request) {
        log.error("系统异常 path={}", request.getRequestURI(), e);
        return R.fail(500, "系统繁忙,请稍后重试");
    }
}
