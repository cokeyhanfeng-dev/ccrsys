package com.ccr.application.integration;

import com.ccr.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 通过 API 网关登录并访问 Mini-App-Plus 授信决议接口。 */
@Slf4j
@Component
public class ApiGatewayCreditResolutionGateway implements CreditResolutionGateway {

    private static final DateTimeFormatter GATEWAY_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final CreditResolutionProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Object tokenLock = new Object();
    private volatile TokenState tokenState;

    public ApiGatewayCreditResolutionGateway(CreditResolutionProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public Optional<ExternalCreditResolution> latest(Integer customerType, String customerId) {
        requireConfigured();
        URI uri = UriComponentsBuilder.fromUriString(join(properties.getBaseUrl(), properties.getLatestPath()))
                .queryParam("customerType", customerType)
                .queryParam("customerId", customerId)
                .build().encode().toUri();
        JsonNode data = callProtectedJson(uri, "GET", null, "查询授信决议");
        if (data == null || data.isNull() || (data.isArray() && data.isEmpty())) {
            return Optional.empty();
        }
        JsonNode resolutionNode = data.isArray() ? data.get(0) : data;
        try {
            ExternalCreditResolution resolution = objectMapper.treeToValue(resolutionNode, ExternalCreditResolution.class);
            if (!StringUtils.hasText(resolution.getResolutionId()) || !StringUtils.hasText(resolution.getResolutionNo())) {
                throw new ServiceException(502, "API 网关返回的授信决议信息不完整");
            }
            if (resolution.getFiles() == null) {
                resolution.setFiles(java.util.List.of());
            }
            return Optional.of(resolution);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(502, "API 网关授信决议响应解析失败");
        }
    }

    @Override
    public DownloadedResolutionFile download(String resolutionId, ExternalResolutionFile file) {
        requireConfigured();
        if (file == null || !StringUtils.hasText(file.getFileId())) {
            throw new ServiceException(502, "授信决议文件标识缺失");
        }
        if (file.getFileSize() != null && file.getFileSize() > properties.getMaxFileSizeBytes()) {
            throw new ServiceException(400, "授信决议附件大小不能超过 10MB");
        }
        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of("resolutionId", resolutionId, "fileId", file.getFileId()));
        } catch (Exception e) {
            throw new ServiceException(500, "授信决议文件兑换请求构造失败");
        }
        JsonNode exchange = callProtectedJson(URI.create(join(properties.getBaseUrl(), properties.getExchangePath())),
                "POST", body, "兑换授信决议文件地址");
        String downloadUrl = text(exchange, "downloadUrl");
        if (!StringUtils.hasText(downloadUrl)) {
            downloadUrl = text(exchange, "url");
        }
        if (!StringUtils.hasText(downloadUrl)) {
            throw new ServiceException(502, "API 网关未返回授信决议文件下载地址");
        }

        URI downloadUri;
        try {
            downloadUri = URI.create(downloadUrl);
        } catch (IllegalArgumentException e) {
            throw new ServiceException(502, "API 网关返回的文件下载地址无效");
        }
        validateDownloadUri(downloadUri);
        HttpRequest downloadRequest = HttpRequest.newBuilder(downloadUri)
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMillis()))
                .GET().build();
        try {
            HttpResponse<InputStream> response = httpClient.send(downloadRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ServiceException(502, "授信决议附件下载失败，远端状态码:" + response.statusCode());
            }
            long contentLength = response.headers().firstValueAsLong(HttpHeaders.CONTENT_LENGTH).orElse(-1L);
            if (contentLength > properties.getMaxFileSizeBytes()) {
                throw new ServiceException(400, "授信决议附件大小不能超过 10MB");
            }
            byte[] content = readLimited(response.body());
            if (content == null || content.length == 0) {
                throw new ServiceException(502, "授信决议附件内容为空");
            }
            if (content.length > properties.getMaxFileSizeBytes()) {
                throw new ServiceException(400, "授信决议附件大小不能超过 10MB");
            }
            String fileName = firstText(exchange, "fileName", file.getFileName(), "授信决议附件-" + file.getFileId());
            String contentType = safeContentType(firstText(exchange, "contentType", response.headers()
                    .firstValue(HttpHeaders.CONTENT_TYPE).orElse(null), file.getContentType(), MediaType.APPLICATION_OCTET_STREAM_VALUE));
            return new DownloadedResolutionFile(file.getFileId(), safeFileName(fileName), contentType, content);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException(503, "授信决议附件下载被中断");
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(503, "授信决议附件下载失败");
        }
    }

    private JsonNode callProtectedJson(URI uri, String method, String body, String operation) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = accessToken();
            GatewayResponse response = send(buildProtectedRequest(uri, method, body, token), operation);
            if (response.unauthorized()) {
                invalidateToken(token);
                if (attempt == 0) {
                    continue;
                }
                throw new ServiceException(502, operation + "失败:API 网关令牌无效");
            }
            return successData(response, operation);
        }
        throw new ServiceException(502, operation + "失败:API 网关令牌无效");
    }

    private HttpRequest buildProtectedRequest(URI uri, String method, String body, String token) {
        HttpRequest.Builder builder = gatewayRequestBuilder(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if ("POST".equals(method)) {
            return builder.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body)).build();
        }
        return builder.GET().build();
    }

    private String accessToken() {
        TokenState current = tokenState;
        Instant refreshAt = Instant.now().plusSeconds(properties.getTokenRefreshSkewSeconds());
        if (current != null && current.expiresAt().isAfter(refreshAt)) {
            return current.value();
        }
        synchronized (tokenLock) {
            current = tokenState;
            refreshAt = Instant.now().plusSeconds(properties.getTokenRefreshSkewSeconds());
            if (current != null && current.expiresAt().isAfter(refreshAt)) {
                return current.value();
            }
            tokenState = login();
            return tokenState.value();
        }
    }

    private TokenState login() {
        URI uri = URI.create(join(properties.getBaseUrl(), properties.getTokenPath()));
        HttpRequest request = gatewayRequestBuilder(uri)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        GatewayResponse response = send(request, "获取 API 网关令牌");
        JsonNode data = successData(response, "获取 API 网关令牌");
        String token = firstText(data, "token", text(data, "accessToken"), text(data, "access_token"));
        if (!StringUtils.hasText(token)) {
            throw new ServiceException(502, "API 网关登录响应未返回令牌");
        }
        long expiresIn = firstPositiveLong(data, "expiresIn", "expires_in", "expireSeconds");
        if (expiresIn <= 0) {
            expiresIn = properties.getTokenFallbackTtlSeconds();
        }
        return new TokenState(token.trim(), Instant.now().plusSeconds(expiresIn));
    }

    private HttpRequest.Builder gatewayRequestBuilder(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMillis()))
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .header(properties.getApiKeyHeader(), properties.getApiKey().trim())
                .header(properties.getSecretHeader(), properties.getSecret().trim())
                .header("X-Sequence-No", UUID.randomUUID().toString().replace("-", ""))
                .header("X-Timestamp", LocalDateTime.now().format(GATEWAY_TIMESTAMP_FORMATTER));
    }

    private GatewayResponse send(HttpRequest request, String operation) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = null;
            if (StringUtils.hasText(response.body())) {
                root = objectMapper.readTree(response.body());
            }
            return new GatewayResponse(response.statusCode(), root);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException(503, operation + "被中断");
        } catch (Exception e) {
            log.warn("{}调用异常:{}", operation, e.getClass().getSimpleName());
            throw new ServiceException(503, operation + "失败");
        }
    }

    private JsonNode successData(GatewayResponse response, String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ServiceException(502, operation + "失败，远端状态码:" + response.statusCode());
        }
        JsonNode root = response.root();
        if (root == null || root.isNull()) {
            throw new ServiceException(502, operation + "失败:远端响应为空");
        }
        if (root.has("code")) {
            int code = root.path("code").asInt();
            if (code != 200 && code != 1 && code != 0) {
                throw new ServiceException(502, operation + "失败:"
                        + root.path("message").asText(root.path("msg").asText("远端返回失败")));
            }
        }
        return root.has("data") ? root.get("data") : root;
    }

    private void invalidateToken(String usedToken) {
        synchronized (tokenLock) {
            if (tokenState != null && tokenState.value().equals(usedToken)) {
                tokenState = null;
            }
        }
    }

    private void validateDownloadUri(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || !StringUtils.hasText(uri.getHost())) {
            throw new ServiceException(502, "授信决议文件下载地址协议不受支持");
        }
        Set<String> allowed = new HashSet<>();
        for (String host : properties.getAllowedDownloadHosts()) {
            if (StringUtils.hasText(host)) {
                allowed.add(host.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (allowed.isEmpty() && StringUtils.hasText(properties.getBaseUrl())) {
            String baseHost = URI.create(properties.getBaseUrl()).getHost();
            if (baseHost != null) {
                allowed.add(baseHost.toLowerCase(Locale.ROOT));
            }
        }
        if (!allowed.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            throw new ServiceException(502, "授信决议文件下载主机未加入允许清单");
        }
    }

    private byte[] readLimited(InputStream inputStream) throws Exception {
        long max = properties.getMaxFileSizeBytes();
        try (InputStream in = inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > max) {
                    throw new ServiceException(400, "授信决议附件大小不能超过 10MB");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private void requireConfigured() {
        if (!properties.isEnabled()) {
            throw new ServiceException(503, "授信决议集成功能未启用");
        }
        if (!StringUtils.hasText(properties.getBaseUrl()) || !StringUtils.hasText(properties.getTokenPath())
                || !StringUtils.hasText(properties.getLatestPath()) || !StringUtils.hasText(properties.getExchangePath())
                || !StringUtils.hasText(properties.getApiKey()) || !StringUtils.hasText(properties.getSecret())
                || !StringUtils.hasText(properties.getApiKeyHeader()) || !StringUtils.hasText(properties.getSecretHeader())) {
            throw new ServiceException(503, "授信决议 API 网关地址、接口路径、API Key 或 Secret 未配置");
        }
        if (properties.getSecret().trim().length() < 16) {
            throw new ServiceException(503, "授信决议 API 网关 Secret 长度不能少于 16 位");
        }
    }

    private static String join(String baseUrl, String path) {
        return baseUrl.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "");
    }

    private static String text(JsonNode node, String field) {
        return node == null || node.isNull() ? null : node.path(field).asText(null);
    }

    private static String firstText(JsonNode node, String field, String... fallbacks) {
        String value = text(node, field);
        if (StringUtils.hasText(value)) {
            return value;
        }
        for (String fallback : fallbacks) {
            if (StringUtils.hasText(fallback)) {
                return fallback;
            }
        }
        return null;
    }

    private static long firstPositiveLong(JsonNode node, String... fields) {
        for (String field : fields) {
            long value = node == null ? 0 : node.path(field).asLong(0);
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }

    private static String safeFileName(String fileName) {
        String cleaned = fileName.replace('\\', '_').replace('/', '_').replace("\r", "").replace("\n", "").trim();
        if (!StringUtils.hasText(cleaned)) {
            return "授信决议附件";
        }
        return cleaned.length() > 255 ? cleaned.substring(0, 255) : cleaned;
    }

    private static String safeContentType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType).toString();
        } catch (Exception ignored) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
    }

    private record TokenState(String value, Instant expiresAt) {
    }

    private record GatewayResponse(int statusCode, JsonNode root) {
        private boolean unauthorized() {
            return statusCode == 401 || (root != null && root.path("code").asInt() == 401);
        }
    }
}
