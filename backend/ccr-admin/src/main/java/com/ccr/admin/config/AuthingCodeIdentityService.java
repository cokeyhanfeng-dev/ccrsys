package com.ccr.admin.config;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ccr.admin.system.domain.CcrSysUser;
import com.ccr.admin.system.mapper.CcrSysUserMapper;
import com.ccr.common.exception.ServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Authing code 兑换与身份查询；平台令牌仅在本次后端请求内使用。 */
@Service
public class AuthingCodeIdentityService {
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private final AuthIntegrationProperties props;
    private final CcrSysUserMapper users;
    private final ObjectMapper json;

    public AuthingCodeIdentityService(AuthIntegrationProperties props, CcrSysUserMapper users, ObjectMapper json) {
        this.props = props;
        this.users = users;
        this.json = json;
    }

    public CcrSysUser verify(String code) {
        if (code == null || code.isBlank() || code.length() > 2048 || code.chars().anyMatch(Character::isISOControl)) {
            throw new ServiceException(400, "单点登录 code 无效，请从统一认证平台重新进入");
        }
        if (!props.isCodeReady()) {
            throw new ServiceException(503, "单点登录尚未配置，请联系管理员");
        }
        // 先校验两个地址，避免因配置错误消耗一次性 code。
        URI tokenUri = gatewayUri(props.getCodeTokenUrl());
        validateUserInfoTemplate();
        String query = Map.of("appCode", props.getCodeAppCode(), "code", code).entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        String token = readToken(request(gatewayUri(tokenUri + (tokenUri.getRawQuery() == null ? "?" : "&") + query)));
        URI userUri = gatewayUri(props.getUserInfoUrl()
                .replace("{appCode}", encode(props.getCodeAppCode()))
                .replace("{token}", encode(token)));
        JsonNode identity = readJson(request(userUri));
        if (identity == null || !identity.isObject()
                || identity.has("error") && !identity.path("error").isNull()
                || identity.has("code") && !"200".equals(identity.path("code").asText())
                || identity.path("success").isBoolean() && !identity.path("success").booleanValue()) throw rejected();
        String username = requiredText(identity.path("loginUserId"), 100);
        CcrSysUser user = users.selectOne(new LambdaQueryWrapper<CcrSysUser>()
                .eq(CcrSysUser::getUsername, username).eq(CcrSysUser::getDelFlag, "0"));
        // 精确匹配，保留工号前导零，不使用姓名、手机号或浏览器传入的身份字段兜底。
        if (user == null || !username.equals(user.getUsername()) || !"0".equals(user.getDelFlag())
                || !"ENABLE".equals(user.getStatus())) {
            throw new ServiceException(401, "单点账号未开通或已停用，请联系管理员");
        }
        return user;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private URI gatewayUri(String url) {
        try {
            URI uri = URI.create(url);
            if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawFragment() == null) return uri;
        } catch (IllegalArgumentException ignored) {
            // 配置错误只返回固定提示，避免暴露路径中的令牌。
        }
        throw new ServiceException(503, "单点登录网关地址配置无效");
    }

    private void validateUserInfoTemplate() {
        String template = props.getUserInfoUrl();
        String expanded = template.replace("{appCode}", "app-code-marker").replace("{token}", "token-marker");
        URI uri = gatewayUri(expanded);
        var segments = java.util.Arrays.asList(uri.getRawPath().split("/", -1));
        if (template.split("\\{appCode\\}", -1).length != 2 || template.split("\\{token\\}", -1).length != 2
                || !segments.contains("app-code-marker") || !segments.contains("token-marker")) {
            throw new ServiceException(503, "单点登录用户地址须包含 {appCode} 和 {token} 路径段");
        }
    }

    private String readToken(String response) {
        String token = response.strip();
        if (token.startsWith("\"")) token = requiredText(readJson(token), 16384);
        // 按当前网关 JWT 文本契约检查格式；身份仍须通过网关查询，不解析或信任令牌声明。
        if (token.length() > 16384 || !token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) throw rejected();
        return token;
    }

    private JsonNode readJson(String response) {
        try {
            return json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(response);
        } catch (Exception ignored) {
            throw rejected();
        }
    }

    private String request(URI uri) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(props.getConnectTimeoutMillis());
            connection.setReadTimeout(props.getRequestTimeoutMillis());
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty(props.getAppIdHeader(), props.getAppId());
            connection.setRequestProperty(props.getApiKeyHeader(), props.getApiKey());
            connection.setRequestProperty("X-Sequence-No", IdUtil.simpleUUID());
            connection.setRequestProperty("X-Timestamp", DateUtil.now());
            if (connection.getResponseCode() != 200) throw rejected();
            byte[] bytes;
            try (var input = connection.getInputStream()) {
                bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (bytes.length > MAX_RESPONSE_BYTES) throw rejected();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            // 原始响应和异常可能包含 code、token、apikey；仅返回固定提示。
            throw new ServiceException(503, "单点认证服务暂时不可用，请从统一认证平台重新进入");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String requiredText(JsonNode node, int maxLength) {
        if (node == null || !node.isTextual() || node.textValue().isBlank() || node.textValue().length() > maxLength
                || node.textValue().chars().anyMatch(Character::isISOControl)) throw rejected();
        return node.textValue();
    }

    private ServiceException rejected() {
        return new ServiceException(401, "单点认证未通过，请从统一认证平台重新进入");
    }
}
