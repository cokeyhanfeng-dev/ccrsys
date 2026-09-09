package com.ccr.admin.message;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 企业微信仅配置独立开关与完整发送地址，凭证复用统一认证。 */
@Data
@Component
@ConfigurationProperties(prefix = "ccr.integration.wechat")
public class WechatMessageProperties {
    private boolean enabled = false;
    private String url;
}
