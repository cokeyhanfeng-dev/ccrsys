package com.ccr.admin.mobile;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 有度免密验票配置；只允许部署环境提供服务地址。 */
@Data
@Component
@ConfigurationProperties(prefix="ccr.mobile.youdu")
public class YouduProperties {
    private boolean enabled;
    /** 完整 URL 模板，必须恰好包含一个 {token}，例如 https://host/verify?token={token}。 */
    private String verifyUrl;
    /** 按有度验票服务实际成功码配置，未确认时保持关闭。 */
    private int successCode = 0;
    private int connectTimeoutMillis = 3000;
    private int readTimeoutMillis = 10000;
}
