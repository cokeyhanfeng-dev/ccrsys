package com.ccr.admin.mobile;

import com.ccr.common.exception.ServiceException;
import org.springframework.stereotype.Service;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

/** 对齐现有 OA getUserByTicket：GET 验票 + 四个认证头，成功响应为纯文本账号。 */
@Service
public class OaIdentityService {
    private final OaProperties properties;

    public OaIdentityService(OaProperties properties) {
        this.properties = properties;
    }

    public String verify(String ticket) {
        if (!properties.isEnabled()) throw new ServiceException(503, "OA 免密登录尚未开通，请联系管理员");
        if (ticket == null || ticket.isBlank() || ticket.length() > 4096)
            throw new ServiceException(401, "OA 票据无效，请从 OA 重新打开应用");
        String template = properties.getVerifyUrl();
        if (template == null || template.indexOf("{ticket}") < 0
                || template.indexOf("{ticket}") != template.lastIndexOf("{ticket}")
                || blank(properties.getAppId()) || blank(properties.getApiKey()))
            throw new ServiceException(503, "OA 验票服务配置不完整");
        HttpURLConnection connection = null;
        try {
            URI fixed = URI.create(template.replace("{ticket}", "ccr-ticket-placeholder"));
            // 防止将票据占位符误配到协议或主机位置；验票目标必须固定。
            if (!Set.of("http", "https").contains(fixed.getScheme()) || fixed.getHost() == null
                    || fixed.getUserInfo() != null || fixed.getFragment() != null
                    || template.substring(0, template.indexOf("{ticket}")).length()
                        < template.indexOf(fixed.getRawAuthority()) + fixed.getRawAuthority().length())
                throw new ServiceException(503, "OA 验票服务地址配置无效");
            URI uri = URI.create(template.replace("{ticket}", URLEncoder.encode(ticket, StandardCharsets.UTF_8)));
            if (!fixed.getHost().equals(uri.getHost()) || fixed.getPort() != uri.getPort())
                throw new ServiceException(503, "OA 验票服务地址配置无效");
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(Math.max(1, Math.min(properties.getConnectTimeoutMillis(), 10000)));
            connection.setReadTimeout(Math.max(1, Math.min(properties.getReadTimeoutMillis(), 15000)));
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "text/plain");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("X-App-Id", properties.getAppId());
            connection.setRequestProperty("apikey", properties.getApiKey());
            connection.setRequestProperty("X-Sequence-No", UUID.randomUUID().toString().replace("-", ""));
            connection.setRequestProperty("X-Timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) throw new ServiceException(401, "OA 票据已失效，请从 OA 重新打开应用");
            if (status != 200) throw new ServiceException(503, "OA 验票服务暂不可用，请稍后从 OA 重试");
            try (var stream = connection.getInputStream()) {
                byte[] bytes = stream.readNBytes(1025);
                if (bytes.length > 1024) throw new ServiceException(503, "OA 验票响应异常");
                return verifiedAccount(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            // 异常可能含 URL、ticket 或认证头，禁止记录异常详情和原始响应。
            throw new ServiceException(503, "OA 身份验证失败，请从 OA 重新打开应用");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static String verifiedAccount(String response) {
        String account = response == null ? "" : response.strip();
        // 现有接口返回账号原文；JSON 错误对象、HTML 页面、空响应均不能当成账号。
        if (!account.matches("[A-Za-z0-9][A-Za-z0-9_.@-]{0,99}")
                || Set.of("null", "false", "true", "error", "fail", "failed", "unauthorized", "forbidden").contains(account.toLowerCase(java.util.Locale.ROOT)))
            throw new ServiceException(401, "OA 验票未返回有效账号，请从 OA 重新打开应用");
        return account;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
