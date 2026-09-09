package com.ccr.admin.message;

import com.ccr.admin.config.AuthIntegrationProperties;
import com.ccr.admin.system.domain.CcrSysUser;
import com.ccr.admin.system.mapper.CcrSysUserMapper;
import com.ccr.common.exception.ServiceException;
import com.ccr.message.domain.CcrNotificationLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WechatMessageSenderTest {
    private final ObjectMapper json = new ObjectMapper();
    private final CcrSysUserMapper users = mock(CcrSysUserMapper.class);
    private final WechatMessageProperties props = new WechatMessageProperties();
    private final AuthIntegrationProperties auth = new AuthIntegrationProperties();
    private final CcrNotificationLog message = new CcrNotificationLog();
    private final CcrSysUser user = new CcrSysUser();
    private final List<String> requests = new ArrayList<>();
    private final List<String> sequences = new ArrayList<>();
    private HttpServer server;
    private WechatMessageSender sender;
    private String response = "{\"msg\":\"success\",\"code\":\"200\"}";
    private int status = 200;
    private int responseDelay;

    @BeforeEach void setup() throws Exception {
        auth.setAppId("test-app"); auth.setApiKey("test-only-key"); auth.setAppCode("test-code");
        props.setEnabled(true);
        message.setRecipientType("USER"); message.setRecipientId("123"); message.setMessageContent("【客户利率审批系统】申请待审批");
        user.setId(123L); user.setUsername("02300001"); user.setStatus("ENABLE"); user.setDelFlag("0");
        when(users.selectById("123")).thenReturn(user);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/common/message/send/ent/wechat", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sequences.add(exchange.getRequestHeaders().getFirst("X-Sequence-No"));
            boolean headers = auth.getAppId().equals(exchange.getRequestHeaders().getFirst(auth.getAppIdHeader()))
                    && auth.getApiKey().equals(exchange.getRequestHeaders().getFirst(auth.getApiKeyHeader()))
                    && exchange.getRequestHeaders().getFirst("X-Timestamp").matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
            if (responseDelay > 0) try { Thread.sleep(responseDelay); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.getResponseHeaders().set("Location", "/common/message/send/ent/wechat");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(headers ? status : 403, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
            exchange.close();
        });
        server.start();
        props.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/common/message/send/ent/wechat");
        sender = new WechatMessageSender(props, auth, users, json);
    }

    @AfterEach void close() { server.stop(0); }

    @Test void sendsExactContractAndSharedHeadersPreservingLeadingZeros() throws Exception {
        sender.send(message);
        assertTrue(requests.get(0).startsWith("POST "));
        assertEquals(json.valueToTree(Map.of("toUser", "02300001", "text", message.getMessageContent())),
                json.readTree(requests.get(0).substring(5)));
        assertTrue(sequences.get(0).matches("[a-f0-9]{32}"));
    }

    @Test void honorsCustomHeaderNamesAndNumericSuccessCodeAndNewSequencePerAttempt() {
        auth.setAppIdHeader("x-app-test"); auth.setApiKeyHeader("x-app-key");
        response = "{\"code\":200}";
        sender.send(message); sender.send(message);
        assertEquals(2, requests.size()); assertNotEquals(sequences.get(0), sequences.get(1));
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "null", "[]", "not-json", "{\"code\":500,\"msg\":\"sensitive-response\"}", "{\"code\":200,\"success\":false}"})
    void rejectsUnconfirmedResponseWithoutLeakingContent(String body) {
        response = body;
        ServiceException error = assertThrows(ServiceException.class, () -> sender.send(message));
        assertFalse(error.getMessage().contains("sensitive-response"));
        assertFalse(error.getMessage().contains(auth.getApiKey()));
    }

    @ParameterizedTest @ValueSource(ints = {302, 401, 500})
    void refusesHttpFailureAndRedirects(int code) {
        status = code;
        assertThrows(ServiceException.class, () -> sender.send(message)); assertEquals(1, requests.size());
    }

    @Test void rejectsOversizedResponse() {
        response = " ".repeat(65537);
        assertThrows(ServiceException.class, () -> sender.send(message));
    }

    @Test void readTimeoutFailsAndDoesNotRetryInline() {
        auth.setRequestTimeoutMillis(20); responseDelay = 150;
        assertThrows(ServiceException.class, () -> sender.send(message)); assertEquals(1, requests.size());
    }

    @Test void disabledOrMissingConfigurationNeverSends() {
        props.setEnabled(false); assertThrows(ServiceException.class, () -> sender.send(message));
        props.setEnabled(true); auth.setApiKey(""); assertThrows(ServiceException.class, () -> sender.send(message));
        assertTrue(requests.isEmpty());
    }

    @Test void disabledDeletedMissingOrBroadcastUserNeverSends() {
        user.setStatus("DISABLE"); assertThrows(ServiceException.class, () -> sender.send(message));
        user.setStatus("ENABLE"); user.setDelFlag("1"); assertThrows(ServiceException.class, () -> sender.send(message));
        user.setDelFlag("0"); user.setUsername("@all"); assertThrows(ServiceException.class, () -> sender.send(message));
        user.setUsername("u1|u2"); assertThrows(ServiceException.class, () -> sender.send(message));
        when(users.selectById("123")).thenReturn(null); assertThrows(ServiceException.class, () -> sender.send(message));
        assertTrue(requests.isEmpty());
    }

    @Test void validatesRecipientAndUtf8TextBeforeSending() {
        message.setRecipientType("ROLE"); assertThrows(ServiceException.class, () -> sender.send(message));
        message.setRecipientType("USER"); message.setMessageContent(" "); assertThrows(ServiceException.class, () -> sender.send(message));
        message.setMessageContent("中".repeat(683)); assertThrows(ServiceException.class, () -> sender.send(message));
        assertTrue(requests.isEmpty());
    }
}
