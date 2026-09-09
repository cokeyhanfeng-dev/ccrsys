package com.ccr.admin.mobile;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** OA 票据认证独立配置，凭证只由部署环境注入。 */
@Data
@Component
@ConfigurationProperties(prefix = "ccr.mobile.oa")
public class OaProperties {
    private boolean enabled;
    /** 服务端固定验票 URL，恰好包含一个 {ticket}，不能由客户端提供。 */
    private String verifyUrl;
    private String appId;
    private String apiKey;
    private int connectTimeoutMillis = 3000;
    private int readTimeoutMillis = 10000;
}
