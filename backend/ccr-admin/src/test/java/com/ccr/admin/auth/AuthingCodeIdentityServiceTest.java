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
    private int userStatus;
    private volatile String delayedPath;
    private static final String TOKEN = "test-header.test-payload.test-signature";
    private final List<String> rawPaths = new CopyOnWriteArrayList<>();
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
        tokenResponse = TOKEN;
        userResponse = "{\"loginUserId\":\"001234\",\"loginUserName\":\"测试用户\",\"loginRoleId\":\"admin\",\"loginDeptId\":null,\"loginDeptName\":\"测试部门\",\"dataLevel\":null,\"extra\":null}";
        tokenStatus = 200;
        userStatus = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            rawPaths.add(exchange.getRequestURI().getRawPath());
            calls.add(exchange.getRequestMethod() + " " + path + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            queries.add(exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery());
            sequences.add(exchange.getRequestHeaders().getFirst("X-Sequence-No"));
            boolean headersValid = "test-app".equals(exchange.getRequestHeaders().getFirst("X-App-Id"))
                    && "test-only-api-key".equals(exchange.getRequestHeaders().getFirst("apikey"))
                    && exchange.getRequestHeaders().getFirst("X-Timestamp") != null;
            if (path.equals(delayedPath)) {
                try { Thread.sleep(200); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            byte[] response = (path.equals("/authing/getAccessTokenByCode") ? tokenResponse : userResponse).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Location", "/userinfo");
            exchange.sendResponseHeaders(headersValid ? (path.equals("/authing/getAccessTokenByCode") ? tokenStatus : userStatus) : 403, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        props.setCodeTokenUrl(base + "/authing/getAccessTokenByCode"); props.setUserInfoUrl(base + "/authing/getLoginUser/{appCode}/{token}");
        service = new AuthingCodeIdentityService(props, users, json);
    }

    @AfterEach void teardown() { server.stop(0); }

    @Test void exchangesWithTwoGetRequestsAndGatewayHeadersAndUsesOnlyLocalRoles() {
        assertSame(user, service.verify("a+b/&=测试"));
        assertEquals("branch_manager", user.getRoleCode());
        assertEquals(2, calls.size());
        assertEquals("GET /authing/getAccessTokenByCode ", calls.get(0), "GET 不携带请求体");
        assertEquals(Map.of("code", "a+b/&=测试", "appCode", "rate-approval"), queryParams(queries.get(0)));
        assertEquals("", queries.get(1));
        assertEquals("GET /authing/getLoginUser/rate-approval/" + TOKEN + " ", calls.get(1));
        assertEquals("ccr-test", props.getAppCode(), "免密应用编码独立于原密码登录配置");
        assertNotNull(sequences.get(0)); assertNotEquals(sequences.get(0), sequences.get(1));
    }

    @Test void acceptsJsonStringTokenAndSurroundingWhitespace() {
        tokenResponse = " \n\"" + TOKEN + "\"\n ";
        assertSame(user, service.verify("code"));
        assertEquals("GET /authing/getLoginUser/rate-approval/" + TOKEN + " ", calls.get(1));
    }

    @Test void getPreservesExistingQueryAndEncodesConfiguredCodeAppCodeAsOnePathSegment() {
        props.setCodeTokenUrl(props.getCodeTokenUrl() + "?channel=test");
        props.setCodeAppCode("ccr+test/&= 测试");
        assertSame(user, service.verify("test-code"));
        assertEquals(Map.of("channel", "test", "appCode", props.getCodeAppCode(), "code", "test-code"), queryParams(queries.get(0)));
        assertEquals("/authing/getLoginUser/ccr%2Btest%2F%26%3D%20%E6%B5%8B%E8%AF%95/" + TOKEN, rawPaths.get(1));
    }

    @Test void acceptsPlainTextTokenWithTrailingNewline() {
        tokenResponse = " \n" + TOKEN + "\n";
        assertSame(user, service.verify("test-code"));
    }

    @ParameterizedTest @ValueSource(strings = {
            "http://gateway.example/userinfo", "http://{appCode}.example/{token}",
            "http://gateway.example/{appCode}?token={token}",
            "http://gateway.example/{appCode}/{token}/{token}",
            "http://gateway.example/{appCode}/prefix{token}",
            "http://user:password@gateway.example/{appCode}/{token}",
            "file:///tmp/{appCode}/{token}", "http://gateway.example/{appCode}/{token}#fragment"})
    void invalidTemplateIsRejectedBeforeConsumingCode(String template) {
        props.setUserInfoUrl(template);
        assertThrows(ServiceException.class, () -> service.verify("code"));
        assertTrue(calls.isEmpty()); verifyNoInteractions(users);
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
        props.setApiKey("test-only-api-key"); props.setCodeAppCode("");
        assertThrows(ServiceException.class, () -> service.verify("code"));
        assertTrue(calls.isEmpty()); verifyNoInteractions(users);
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"code\":401,\"data\":\"must-not-use\"}",
            "{\"code\":200,\"success\":false,\"data\":\"must-not-use\"}",
            "{\"data\":\"missing-status\"}",
            "{\"code\":200,\"data\":123}",
            "{\"code\":200,\"data\":\"\"}", "[]", "null", "<html>error</html>", "", " ", "gateway error", "a.b", "a.b.c/evil", "a.b.c\nsecret", "\"a.b.c\" {}"})
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

    @ParameterizedTest @ValueSource(strings = {
            "{\"loginUserName\":\"001234\"}", "{\"loginUserId\":1234}", "{\"loginUserId\":null}",
            "{\"loginUserId\":\"\"}", "{\"loginUserId\":\" \"}", "{\"loginUserId\":\"001234\\n\"}",
            "{\"code\":401,\"loginUserId\":\"001234\"}",
            "{\"success\":false,\"loginUserId\":\"001234\"}",
            "{\"error\":\"denied\",\"loginUserId\":\"001234\"}",
            "{\"data\":{\"userBasicInfo\":{\"username\":\"001234\"}}}",
            "null", "[]", "<html>error</html>", "{\"loginUserId\":\"001234\"} {}"})
    void invalidUserResponseNeverQueriesLocalUser(String response) {
        userResponse = response;
        var error = assertThrows(ServiceException.class, () -> service.verify("secret-code"));
        assertFalse(error.getMessage().contains(TOKEN));
        assertEquals(2, calls.size()); verifyNoInteractions(users);
    }

    @ParameterizedTest @ValueSource(ints = {302, 401, 500})
    void userHttpFailureIsNotFollowedOrRetried(int status) {
        userStatus = status;
        assertThrows(ServiceException.class, () -> service.verify("code"));
        assertEquals(2, calls.size()); verifyNoInteractions(users);
    }

    @Test void oversizedTokenAndAccountAreRejected() {
        tokenResponse = "a.b." + "c".repeat(16384);
        assertThrows(ServiceException.class, () -> service.verify("code"));
        assertEquals(1, calls.size());
        tokenResponse = TOKEN;
        userResponse = "{\"loginUserId\":\"" + "a".repeat(101) + "\"}";
        assertThrows(ServiceException.class, () -> service.verify("another-code"));
        verifyNoInteractions(users);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void eitherGatewayReadTimeoutRejectsWithoutRetryOrCredentialLeak(boolean tokenStep) {
        props.setRequestTimeoutMillis(50);
        delayedPath = tokenStep ? "/authing/getAccessTokenByCode" : "/authing/getLoginUser/rate-approval/" + TOKEN;
        var error = assertThrows(ServiceException.class, () -> service.verify("secret-code"));
        assertFalse(error.getMessage().contains("secret-code"));
        assertFalse(error.getMessage().contains(TOKEN));
        assertEquals(tokenStep ? 1 : 2, calls.size());
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
