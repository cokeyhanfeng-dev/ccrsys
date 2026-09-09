package com.ccr.admin.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 仅接受平台 code，应用标识和网关凭证由服务端配置。 */
public record CodeLoginRequest(
        @NotBlank(message = "单点登录 code 必填")
        @Size(max = 2048, message = "单点登录 code 长度超限") String code) {
}
