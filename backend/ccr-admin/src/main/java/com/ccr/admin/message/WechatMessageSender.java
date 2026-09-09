package com.ccr.admin.message;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.ccr.admin.config.AuthIntegrationProperties;
import com.ccr.admin.system.domain.CcrSysUser;
import com.ccr.admin.system.mapper.CcrSysUserMapper;
import com.ccr.common.exception.ServiceException;
import com.ccr.message.domain.CcrNotificationLog;
import com.ccr.message.service.sender.MessageSender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 复用单点登录网关头，按本地账号（绩效码字符串）逐人发送纯文本。 */
@Component
public class WechatMessageSender implements MessageSender {
    private final WechatMessageProperties props;
    private final AuthIntegrationProperties auth;
    private final CcrSysUserMapper users;
    private final ObjectMapper json;

    public WechatMessageSender(WechatMessageProperties props, AuthIntegrationProperties auth,
                               CcrSysUserMapper users, ObjectMapper json) {
        this.props = props;
        this.auth = auth;
        this.users = users;
        this.json = json;
    }

    @Override
    public boolean supports(String channel) {
        return "WECHAT".equalsIgnoreCase(channel);
    }

    @Override
    public void send(CcrNotificationLog message) {
        if (!props.isEnabled() || !StrUtil.isAllNotBlank(props.getUrl(), auth.getAppId(), auth.getApiKey(),
                auth.getAppIdHeader(), auth.getApiKeyHeader())) {
            throw failure("企业微信提醒未启用或网关配置不完整");
        }
        if (!"USER".equals(message.getRecipientType()) || message.getRecipientId() == null
                || !message.getRecipientId().matches("[0-9]{1,19}")) {
            throw failure("企业微信提醒必须指定本地用户ID");
        }
        CcrSysUser user = users.selectById(message.getRecipientId());
        if (user == null || !"ENABLE".equals(user.getStatus()) || !"0".equals(user.getDelFlag())
                || user.getUsername() == null || !user.getUsername().matches("[A-Za-z0-9_.-]{1,100}")) {
            throw failure("企业微信接收账号不存在、已停用或格式无效");
        }
        // 禁止广播/多人分隔符，保留绩效码前导零；长度按企微文本的 UTF-8 字节控制。
        String content = message.getMessageContent();
        if (content == null || content.isBlank() || content.getBytes(StandardCharsets.UTF_8).length > 2048) {
            throw failure("企业微信文本为空或超过2048字节");
        }
        HttpURLConnection connection = null;
        try {
            URI uri = URI.create(props.getUrl());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
                throw failure("企业微信网关地址配置无效");
            }
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(boundedTimeout(auth.getConnectTimeoutMillis(), 3000));
            connection.setReadTimeout(boundedTimeout(auth.getRequestTimeoutMillis(), 10000));
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty(auth.getAppIdHeader(), auth.getAppId());
            connection.setRequestProperty(auth.getApiKeyHeader(), auth.getApiKey());
            connection.setRequestProperty("X-Sequence-No", IdUtil.simpleUUID());
            connection.setRequestProperty("X-Timestamp", DateUtil.now());
            byte[] body = json.writeValueAsBytes(Map.of("toUser", user.getUsername(), "text", content));
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (var output = connection.getOutputStream()) {
                output.write(body);
            }
            if (connection.getResponseCode() != 200) throw failure("企业微信网关HTTP状态异常");
            byte[] bytes;
            try (var input = connection.getInputStream()) {
                bytes = input.readNBytes(65537);
            }
            if (bytes.length > 65536) throw failure("企业微信网关响应超限");
            JsonNode response = json.readTree(bytes);
            if (response == null || !response.isObject()
                    || !"200".equals(response.path("code").asText())
                    || response.path("success").isBoolean() && !response.path("success").booleanValue()) {
                throw failure("企业微信网关未确认发送成功");
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            // 原始异常、响应可能含凭证、人员和正文，不写入错误日志。
            throw failure("企业微信网关调用失败或响应无效");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private int boundedTimeout(int value, int fallback) {
        return value > 0 ? Math.min(value, 30000) : fallback;
    }

    private ServiceException failure(String message) {
        return new ServiceException(503, message);
    }
}
