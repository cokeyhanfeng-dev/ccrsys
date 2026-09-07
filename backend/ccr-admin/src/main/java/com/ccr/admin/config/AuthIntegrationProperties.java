package com.ccr.admin.config;

import cn.hutool.core.util.StrUtil;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 统一认证(SSO)登录接入配置(2026-09-07,admin 除外)。
 * 访问凭证 apiKey 仅允许由部署环境注入(CCR_INTEGRATION_AUTH_API_KEY),代码与默认配置均不提供测试口令;
 * 配置驱动:enabled 默认 true——url/apiKey 齐备(已接入)时 admin 除外强制走统一认证;
 * 未注入凭证(生产尚未接入)自动回退本地 BCrypt,不锁死登录。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ccr.integration.auth")
public class AuthIntegrationProperties {

    /** 总开关:false 一律本地 BCrypt;true 时 admin 除外且已接入(isReady)则走统一认证判密。 */
    private boolean enabled = true;
    /** 统一认证登录验证地址。 */
    private String url;
    /** 应用标识(X-App-Id)。 */
    private String appId;
    /** 访问凭证 apikey:仅部署环境注入 ${CCR_INTEGRATION_AUTH_API_KEY:},禁止写死入库。 */
    private String apiKey;
    /** 请求体 appCode(接入方系统应用编码,经拍板照参考代码传 65f3b6d259663202ce3277e8)。 */
    private String appCode;
    private String appIdHeader = "X-App-Id";
    private String apiKeyHeader = "apikey";
    private int connectTimeoutMillis = 3000;
    private int requestTimeoutMillis = 10000;

    /** 是否已接入:url 与 apiKey 齐备才算;enabled=true 但未齐备视为未接入,登录回退本地 BCrypt。 */
    public boolean isReady() {
        return StrUtil.isNotBlank(url) && StrUtil.isNotBlank(apiKey);
    }
}
