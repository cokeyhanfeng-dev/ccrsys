package com.ccr.admin.auth;

import com.ccr.admin.config.AuthIntegrationProperties;
import com.ccr.admin.config.AuthingCodeIdentityService;
import com.ccr.admin.system.domain.CcrSysUser;
import com.ccr.admin.system.mapper.CcrSysUserMapper;
import com.ccr.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthingCodeIdentityServiceTest {
    private HttpServer server;
    private AuthIntegrationProperties props;
    private CcrSysUserMapper users;
    private AuthingCodeIdentityService service;
    private CcrSysUser user;
    private String tokenResponse;
    private String userResponse;
    private int tokenStatus;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> sequences = new CopyOnWriteArrayList<>();
    private final List<String> queries = new CopyOnWriteArrayList<>();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void setup() throws Exception {
        props = new AuthIntegrationProperties();
        props.setCodeEnabled(true);
        props.setAppId("test-app"); props.setApiKey("test-only-api-key"); props.setAppCode("ccr-test");
        users = mock(CcrSysUserMapper.class);
        user = new CcrSysUser(); user.setId(42L); user.setUsername("001234");
        user.setStatus("ENABLE"); user.setDelFlag("0"); user.setRoleCode("branch_manager");
        when(users.selectOne(any())).thenReturn(user);
        tokenResponse = "{\"msg\":\"交易成功\",\"code\":\"200\",\"data\":\"platform-token\"}";
        userResponse = "{\"msg\":\"交易成功\",\"code\":\"200\",\"data\":{\"userBasicInfo\":{\"username\":\"001234\",\"name\":\"测试用户\"},\"roles\":[\"admin\"]}}";
        tokenStatus = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            calls.add(exchange.getRequestMethod() + " " + path + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            queries.add(exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery());
            sequences.add(exchange.getRequestHeaders().getFirst("X-Sequence-No"));
            boolean headersValid = "test-app".equals(exchange.getRequestHeaders().getFirst("X-App-Id"))
                    && "test-only-api-key".equals(exchange.getRequestHeaders().getFirst("apikey"))
                    && exchange.getRequestHeaders().getFirst("X-Timestamp") != null;
            byte[] response = (path.equals("/token") ? tokenResponse : userResponse).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Location", "/userinfo");
            exchange.sendResponseHeaders(headersValid ? (path.equals("/token") ? tokenStatus : 200) : 403, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        props.setCodeTokenUrl(base + "/token"); props.setUserInfoUrl(base + "/userinfo");
        service = new AuthingCodeIdentityService(props, users, json);
    }

    @AfterEach void teardown() { server.stop(0); }

    @Test void exchangesWithGetThenPostAndGatewayHeadersAndUsesOnlyLocalRoles() throws Exception {
        assertSame(user, service.verify("a+b/&=测试"));
        assertEquals("branch_manager", user.getRoleCode());
        assertEquals(2, calls.size());
        assertEquals("GET /token ", calls.get(0), "GET 不携带 JSON 请求体");
        assertEquals(Map.of("code", "a+b/&=测试", "appCode", "ccr-test"), queryParams(queries.get(0)));
        assertEquals("", queries.get(1), "平台 token 仅放在 POST 请求体中");
        assertEquals("ccr-test", json.readTree(calls.get(1).substring("POST /userinfo ".length())).path("appCode").asText());
        assertEquals("platform-token", json.readTree(calls.get(1).substring("POST /userinfo ".length())).path("token").asText());
        assertNotNull(sequences.get(0)); assertNotEquals(sequences.get(0), sequences.get(1));
    }

    @Test void configuredResponsePathsAndSuccessCodeAreRespected() {
        props.setCodeTokenPath("data.accessToken"); props.setUserNamePath("data.user.account");
        props.setResponseCodePath("statusCode"); props.setResponseSuccessCode("0");
        tokenResponse = "{\"statusCode\":0,\"data\":{\"accessToken\":\"platform-token\"}}";
        userResponse = "{\"statusCode\":0,\"data\":{\"user\":{\"account\":\"001234\"}}}";
        assertSame(user, service.verify("code"));
    }

    @Test void getPreservesExistingQueryAndEncodesConfiguredAppCode() throws Exception {
        props.setCodeTokenUrl(props.getCodeTokenUrl() + "?channel=test");
        props.setAppCode("ccr+test/&=");
        assertSame(user, service.verify("test-code"));
        assertEquals(Map.of("channel", "test", "appCode", "ccr+test/&=", "code", "test-code"), queryParams(queries.get(0)));
        assertEquals(props.getAppCode(), json.readTree(calls.get(1).substring("POST /userinfo ".length())).path("appCode").asText());
    }

    @Test void numericSuccessCodeRemainsCompatible() {
        tokenResponse = tokenResponse.replace("\"200\"", "200");
        userResponse = userResponse.replace("\"200\"", "200");
        assertSame(user, service.verify("test-code"));
    }

    private Map<String, String> queryParams(String query) {
        return java.util.Arrays.stream(query.split("&"))
                .map(part -> part.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(
                        pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "\ncode"})
    void invalidCodeDoesNotCallGateway(String code) {
        assertThrows(ServiceException.class, () -> service.verify(code));
        assertTrue(calls.isEmpty()); verifyNoInteractions(users);
    }

    @Test void nullAndOversizedCodesAreRejected() {
        assertThrows(ServiceException.class, () -> service.verify(null));
        assertThrows(ServiceException.class, () -> service.verify("a".repeat(2049)));
        assertTrue(calls.isEmpty());
    }

    @Test void disabledOrIncompleteConfigurationNeverFallsBack() {
        props.setCodeEnabled(false); assertThrows(ServiceException.class, () -> service.verify("code"));
        props.setCodeEnabled(true); props.setApiKey(""); assertThrows(ServiceException.class, () -> service.verify("code"));
        assertTrue(calls.isEmpty()); verifyNoInteractions(users);
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"code\":401,\"data\":\"must-not-use\"}",
            "{\"code\":200,\"success\":false,\"data\":\"must-not-use\"}",
            "{\"data\":\"missing-status\"}",
            "{\"code\":200,\"data\":123}",
            "{\"code\":200,\"data\":\"\"}", "[]", "null", "<html>error</html>"})
    void malformedOrUnsuccessfulTokenResponseStopsBeforeIdentityLookup(String response) {
        tokenResponse = response;
        var error = assertThrows(ServiceException.class, () -> service.verify("secret-code"));
        assertFalse(error.getMessage().contains("secret-code"));
        assertEquals(1, calls.size()); verifyNoInteractions(users);
    }

    @ParameterizedTest @ValueSource(ints = {302, 401, 500})
    void httpFailureIsNotFollowedOrRetried(int status) {
        tokenStatus = status;
        assertThrows(ServiceException.class, () -> service.verify("code"));
        assertEquals(1, calls.size()); verifyNoInteractions(users);
    }

    @Test void invalidUserResponseNeverQueriesLocalUser() {
        userResponse = "{\"code\":401,\"data\":{\"userBasicInfo\":{\"username\":\"001234\"}}}";
        assertThrows(ServiceException.class, () -> service.verify("code"));
        userResponse = "{\"code\":200,\"data\":{\"userBasicInfo\":{\"name\":\"001234\"}}}";
        assertThrows(ServiceException.class, () -> service.verify("another-code"));
        verifyNoInteractions(users);
    }

    @Test void missingDisabledDeletedOrMismatchedLocalAccountCannotLogin() {
        when(users.selectOne(any())).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.verify("code"));
        when(users.selectOne(any())).thenReturn(user);
        user.setStatus("DISABLE"); assertThrows(ServiceException.class, () -> service.verify("code"));
        user.setStatus("ENABLE"); user.setDelFlag("1"); assertThrows(ServiceException.class, () -> service.verify("code"));
        user.setDelFlag("0"); user.setUsername("1234"); assertThrows(ServiceException.class, () -> service.verify("code"));
    }

    @Test void consumedCodeIsVerifiedAgainAndRejectedByGateway() {
        assertSame(user, service.verify("one-use-code"));
        tokenResponse = "{\"code\":401,\"msg\":\"consumed\"}";
        assertThrows(ServiceException.class, () -> service.verify("one-use-code"));
        assertEquals(3, calls.size()); verify(users, times(1)).selectOne(any());
    }

    @Test void oversizedResponseIsRejected() {
        tokenResponse = " ".repeat(256 * 1024 + 1);
        assertThrows(ServiceException.class, () -> service.verify("code"));
        verifyNoInteractions(users);
    }
}
