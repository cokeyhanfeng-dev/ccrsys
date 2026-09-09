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
        JsonNode tokenResponse = request("GET", props.getCodeTokenUrl(), Map.of("appCode", props.getAppCode(), "code", code));
        String token = requiredText(tokenResponse, props.getCodeTokenPath(), 16384);
        JsonNode identity = request("POST", props.getUserInfoUrl(), Map.of("appCode", props.getAppCode(), "token", token));
        String username = requiredText(identity, props.getUserNamePath(), 100);
        CcrSysUser user = users.selectOne(new LambdaQueryWrapper<CcrSysUser>()
                .eq(CcrSysUser::getUsername, username).eq(CcrSysUser::getDelFlag, "0"));
        // 精确匹配，保留工号前导零，不使用姓名、手机号或浏览器传入的身份字段兜底。
        if (user == null || !username.equals(user.getUsername()) || !"0".equals(user.getDelFlag())
                || !"ENABLE".equals(user.getStatus())) {
            throw new ServiceException(401, "单点账号未开通或已停用，请联系管理员");
        }
        return user;
    }

    private JsonNode request(String method, String url, Map<String, String> params) {
        HttpURLConnection connection = null;
        try {
            URI uri = URI.create(url);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
                throw new ServiceException(503, "单点登录网关地址配置无效");
            }
            if ("GET".equals(method)) {
                // 授权码必须作为单个查询参数编码，避免 +、&、= 等字符改变原值。
                String query = params.entrySet().stream()
                        .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                                + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                        .collect(java.util.stream.Collectors.joining("&"));
                uri = URI.create(url + (uri.getRawQuery() == null ? "?" : "&") + query);
            }
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(props.getConnectTimeoutMillis());
            connection.setReadTimeout(props.getRequestTimeoutMillis());
            connection.setRequestMethod(method);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty(props.getAppIdHeader(), props.getAppId());
            connection.setRequestProperty(props.getApiKeyHeader(), props.getApiKey());
            connection.setRequestProperty("X-Sequence-No", IdUtil.simpleUUID());
            connection.setRequestProperty("X-Timestamp", DateUtil.now());
            if ("POST".equals(method)) {
                connection.setDoOutput(true);
                byte[] payload = json.writeValueAsBytes(params);
                connection.setFixedLengthStreamingMode(payload.length);
                try (var output = connection.getOutputStream()) {
                    output.write(payload);
                }
            }
            if (connection.getResponseCode() != 200) throw rejected();
            byte[] bytes;
            try (var input = connection.getInputStream()) {
                bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (bytes.length > MAX_RESPONSE_BYTES) throw rejected();
            JsonNode response = json.readTree(new String(bytes, StandardCharsets.UTF_8));
            JsonNode status = at(response, props.getResponseCodePath());
            if (response == null || !response.isObject() || !status.isValueNode() || status.isNull()
                    || !props.getResponseSuccessCode().equals(status.asText())
                    || response.path("success").isBoolean() && !response.path("success").booleanValue()) {
                throw rejected();
            }
            return response;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            // 原始响应和异常可能包含 code、token、apikey；仅返回固定提示。
            throw new ServiceException(503, "单点认证服务暂时不可用，请从统一认证平台重新进入");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String requiredText(JsonNode response, String path, int maxLength) {
        JsonNode node = at(response, path);
        if (!node.isTextual() || node.textValue().isBlank() || node.textValue().length() > maxLength
                || node.textValue().chars().anyMatch(Character::isISOControl)) throw rejected();
        return node.textValue();
    }

    private JsonNode at(JsonNode node, String path) {
        if (node == null) return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        for (String part : path.split("\\.", -1)) node = node.path(part);
        return node;
    }

    private ServiceException rejected() {
        return new ServiceException(401, "单点认证未通过，请从统一认证平台重新进入");
    }
}
