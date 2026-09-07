package com.ccr.admin.config;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.ccr.common.exception.ServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一认证(SSO)登录验证(2026-09-07 接入,admin 除外走本服务判密码,角色/机构仍取本地库):
 * POST {url} 体 {userName,password,appCode},头 X-App-Id/apikey/X-Sequence-No/X-Timestamp(请求格式照参考 loginAuth)。
 * 判定约定(用户拍板):HTTP 200 即验证通过;非 200 判"用户名或密码错误";网络/超时/服务异常一律拒登,不回退本地密码。
 * 实现用 JDK 内置 java.net.http.HttpClient,零第三方依赖(同 ApiGatewayCreditResolutionGateway 惯例)。
 */
@Service
@Slf4j
public class SsoAuthService {

    private final AuthIntegrationProperties props;
    private final HttpClient http;

    public SsoAuthService(AuthIntegrationProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMillis()))
                .build();
    }

    /** 验证用户名密码;通过静默返回,未通过或认证服务异常抛 401 ServiceException。 */
    public void verify(String userName, String password) {
        if (StrUtil.isBlank(props.getUrl()) || StrUtil.isBlank(props.getApiKey())) {
            log.error("统一认证已启用但未配置 url/apiKey,请部署环境注入 CCR_INTEGRATION_AUTH_URL/AUTH_API_KEY");
            throw new ServiceException(401, "认证服务配置缺失,请联系管理员");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userName", userName);
        body.put("password", password);
        body.put("appCode", props.getAppCode());
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(props.getUrl()))
                    .timeout(Duration.ofMillis(props.getRequestTimeoutMillis()))
                    .header("Content-Type", "application/json")
                    .header(props.getAppIdHeader(), props.getAppId())
                    .header(props.getApiKeyHeader(), props.getApiKey())
                    .header("X-Sequence-No", IdUtil.simpleUUID())
                    .header("X-Timestamp", DateUtil.now())
                    .POST(HttpRequest.BodyPublishers.ofString(JSONUtil.toJsonStr(body)))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                // 照参考 loginAuth:200 且响应体可解析为 JSON(用户信息)才算验证通过;body 空/非 JSON 判失败
                String respBody = resp.body();
                if (StrUtil.isNotBlank(respBody) && JSONUtil.isJson(respBody)) {
                    return;
                }
                log.warn("统一认证 200 但响应体非 JSON:userName={} resp={}", userName,
                        StrUtil.maxLength(respBody, 200));
                throw new ServiceException(401, "用户名或密码错误");
            }
            log.warn("统一认证未通过:userName={} status={} resp={}", userName, resp.statusCode(),
                    StrUtil.maxLength(resp.body(), 200));
            throw new ServiceException(401, "用户名或密码错误");
        } catch (ServiceException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("统一认证验证被中断:userName={} {}", userName, e.getMessage());
            throw new ServiceException(401, "认证服务暂时不可用,请稍后重试");
        } catch (Exception e) {
            log.warn("统一认证验证异常:userName={} {}", userName, e.getMessage());
            throw new ServiceException(401, "认证服务暂时不可用,请稍后重试");
        }
    }
}
