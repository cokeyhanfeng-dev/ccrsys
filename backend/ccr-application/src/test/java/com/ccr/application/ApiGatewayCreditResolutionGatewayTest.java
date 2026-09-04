package com.ccr.application;

import com.ccr.application.integration.ApiGatewayCreditResolutionGateway;
import com.ccr.application.integration.CreditResolutionProperties;
import com.ccr.application.integration.DownloadedResolutionFile;
import com.ccr.application.integration.ExternalCreditResolution;
import com.ccr.application.integration.ExternalResolutionFile;
import com.ccr.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiGatewayCreditResolutionGatewayTest {

    private HttpServer server;
    private CreditResolutionProperties properties;
    private ApiGatewayCreditResolutionGateway gateway;
    private AtomicInteger loginCount;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        properties = new CreditResolutionProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("ccrsys-test-api-key");
        properties.setSecret("test-only-secret-with-sufficient-entropy");
        properties.setAllowedDownloadHosts(List.of("127.0.0.1"));
        loginCount = new AtomicInteger();
        server.createContext("/auth/token", exchange -> {
            loginCount.incrementAndGet();
            assertGatewayCredentials(exchange);
            json(exchange, 200, "{\"code\":200,\"data\":{\"token\":\"service-token-1\",\"expiresIn\":3600}}");
        });
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        gateway = new ApiGatewayCreditResolutionGateway(properties, objectMapper);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void latest_groupId_logsInAndReturnsResolutionMetadata() {
        server.createContext("/miniapp/creditResolution/ccr/latest", exchange -> {
            assertTrue(exchange.getRequestURI().getQuery().contains("customerType=3"));
            assertTrue(exchange.getRequestURI().getQuery().contains("customerId=GROUP001"));
            assertProtectedHeaders(exchange, "service-token-1");
            json(exchange, 200, """
                    {"code":200,"data":{"resolutionId":"91","resolutionNo":"SX-2026-0091","customerType":3,
                    "customerId":"GROUP001","customerName":"测试集团","versionNo":2,"uploadTime":"2026-09-02 09:30:00",
                    "files":[{"fileId":"801","fileName":"授信决议.pdf","fileSize":4,"contentType":"application/pdf"}]}}
                    """);
        });

        ExternalCreditResolution result = gateway.latest(3, "GROUP001").orElseThrow();

        assertEquals("SX-2026-0091", result.getResolutionNo());
        assertEquals("801", result.getFiles().get(0).getFileId());
        assertEquals(1, loginCount.get());
    }

    @Test
    void latest_reusesUnexpiredServiceToken() {
        server.createContext("/miniapp/creditResolution/ccr/latest", exchange -> {
            assertProtectedHeaders(exchange, "service-token-1");
            json(exchange, 200, "{\"code\":200,\"data\":null}");
        });

        assertTrue(gateway.latest(2, "C001").isEmpty());
        assertTrue(gateway.latest(2, "C001").isEmpty());
        assertEquals(1, loginCount.get());
    }

    @Test
    void latest_unauthorized_refreshesTokenAndRetriesOnce() {
        server.removeContext("/auth/token");
        server.createContext("/auth/token", exchange -> {
            int count = loginCount.incrementAndGet();
            json(exchange, 200, "{\"code\":200,\"data\":{\"token\":\"service-token-" + count
                    + "\",\"expiresIn\":3600}}");
        });
        AtomicInteger latestCount = new AtomicInteger();
        server.createContext("/miniapp/creditResolution/ccr/latest", exchange -> {
            int count = latestCount.incrementAndGet();
            if (count == 1) {
                assertProtectedHeaders(exchange, "service-token-1");
                json(exchange, 401, "{\"code\":401,\"msg\":\"token expired\"}");
                return;
            }
            assertProtectedHeaders(exchange, "service-token-2");
            json(exchange, 200, "{\"code\":200,\"data\":null}");
        });

        assertTrue(gateway.latest(2, "C001").isEmpty());
        assertEquals(2, loginCount.get());
        assertEquals(2, latestCount.get());
    }

    @Test
    void download_exchangesPrivateUrlAndReturnsBytes() {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/miniapp/creditResolution/ccr/files/exchange", exchange -> {
            assertProtectedHeaders(exchange, "service-token-1");
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            json(exchange, 200, "{\"code\":200,\"data\":{\"downloadUrl\":\"" + properties.getBaseUrl()
                    + "/private-file\",\"fileName\":\"集团授信决议.pdf\",\"contentType\":\"application/pdf\"}}");
        });
        server.createContext("/private-file", exchange -> bytes(exchange, 200, new byte[]{1, 2, 3, 4}, "application/pdf"));
        ExternalResolutionFile file = new ExternalResolutionFile();
        file.setFileId("801");
        file.setFileName("原文件.pdf");

        DownloadedResolutionFile result = gateway.download("91", file);

        assertTrue(requestBody.get().contains("\"resolutionId\":\"91\""));
        assertTrue(requestBody.get().contains("\"fileId\":\"801\""));
        assertEquals("集团授信决议.pdf", result.fileName());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, result.content());
    }

    @Test
    void download_rejectsHostOutsideAllowlist() {
        server.createContext("/miniapp/creditResolution/ccr/files/exchange", exchange -> json(exchange, 200,
                "{\"code\":200,\"data\":{\"downloadUrl\":\"http://169.254.169.254/latest/meta-data\"}}"));
        ExternalResolutionFile file = new ExternalResolutionFile();
        file.setFileId("801");

        ServiceException error = assertThrows(ServiceException.class, () -> gateway.download("91", file));

        assertTrue(error.getMessage().contains("主机未加入允许清单"));
    }

    @Test
    void download_streamExceedsLimit_stopsAndRejects() {
        properties.setMaxFileSizeBytes(3);
        server.createContext("/miniapp/creditResolution/ccr/files/exchange", exchange -> json(exchange, 200,
                "{\"code\":200,\"data\":{\"downloadUrl\":\"" + properties.getBaseUrl() + "/large-file\"}}"));
        server.createContext("/large-file", exchange -> bytes(exchange, 200, new byte[]{1, 2, 3, 4}, "application/pdf"));
        ExternalResolutionFile file = new ExternalResolutionFile();
        file.setFileId("801");

        ServiceException error = assertThrows(ServiceException.class, () -> gateway.download("91", file));

        assertTrue(error.getMessage().contains("不能超过 10MB"));
    }

    @Test
    void latest_weakCredential_rejectsBeforeLogin() {
        properties.setSecret("123456");

        ServiceException error = assertThrows(ServiceException.class, () -> gateway.latest(2, "C001"));

        assertTrue(error.getMessage().contains("长度不能少于 16 位"));
        assertEquals(0, loginCount.get());
    }

    private void assertProtectedHeaders(HttpExchange exchange, String token) {
        assertGatewayCredentials(exchange);
        assertEquals("Bearer " + token, exchange.getRequestHeaders().getFirst("Authorization"));
    }

    private void assertGatewayCredentials(HttpExchange exchange) {
        assertEquals("ccrsys-test-api-key", exchange.getRequestHeaders().getFirst("apikey"));
        assertEquals("test-only-secret-with-sufficient-entropy", exchange.getRequestHeaders().getFirst("secret"));
        assertTrue(exchange.getRequestHeaders().getFirst("X-Sequence-No").matches("[0-9a-f]{32}"));
        assertTrue(exchange.getRequestHeaders().getFirst("X-Timestamp").matches("\\d{14}"));
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        bytes(exchange, status, body.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private static void bytes(HttpExchange exchange, int status, byte[] body, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
