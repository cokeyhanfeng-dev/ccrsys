package com.ccr.application.integration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部授信决议 API 网关配置。
 *
 * <p>访问凭证仅允许由部署环境注入，代码和默认配置均不提供测试口令。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ccr.integration.credit-resolution")
public class CreditResolutionProperties {

    /** 总开关；未配置时保持关闭，不影响原申请流程。 */
    private boolean enabled = false;

    /** API 网关根地址，例如 https://api-gateway.internal.example。 */
    private String baseUrl;

    /** 使用 API Key 和 Secret 换取服务令牌的相对路径。 */
    private String tokenPath = "/auth/token";

    /** 查询某客户或集团最新有效决议的相对路径。 */
    private String latestPath = "/miniapp/creditResolution/ccr/latest";

    /** 将私有桶文件 ID 兑换为短期下载地址的相对路径。 */
    private String exchangePath = "/miniapp/creditResolution/ccr/files/exchange";

    /** API 网关分配给 CCRSYS 的 API Key。 */
    private String apiKey;

    /** API 网关分配给 CCRSYS 的 Secret；部署时至少使用 16 位高熵随机值。 */
    private String secret;

    private String apiKeyHeader = "apikey";
    private String secretHeader = "secret";
    private long tokenFallbackTtlSeconds = 14400;
    private long tokenRefreshSkewSeconds = 60;

    private int connectTimeoutMillis = 3000;
    private int requestTimeoutMillis = 10000;
    private long maxFileSizeBytes = 10L * 1024 * 1024;
    private int maxFilesPerResolution = 10;
    private long maxTotalSizeBytes = 30L * 1024 * 1024;

    /**
     * 允许下载的私有桶主机名。为空时仅允许与 API 网关根地址相同的主机，防止兑换结果造成 SSRF。
     */
    private List<String> allowedDownloadHosts = new ArrayList<>();
}
