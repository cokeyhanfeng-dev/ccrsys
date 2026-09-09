package com.ccr.admin.mobile;

import com.ccr.common.exception.ServiceException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class OaIdentityServiceTest {
    @Test void acceptsOnlyPlainAccountAndRejectsErrorBodies() {
        assertEquals("001234", OaIdentityService.verifiedAccount("001234\n"));
        assertEquals("test.approver", OaIdentityService.verifiedAccount("test.approver"));
        for (String body : new String[]{"", "null", "false", "ERROR", "<html>error</html>", "{\"account\":\"admin\"}", "\"admin\"", "a b", "x".repeat(101)})
            assertThrows(ServiceException.class, () -> OaIdentityService.verifiedAccount(body));
    }

    @Test void disabledAndIncompleteConfigurationCannotVerify() {
        var p = new OaProperties();
        var service = new OaIdentityService(p);
        assertThrows(ServiceException.class, () -> service.verify("ticket"));
        p.setEnabled(true);
        assertThrows(ServiceException.class, () -> service.verify("ticket"));
        p.setVerifyUrl("https://example.invalid/verify?ticket={ticket}");
        p.setAppId("fixture-app");
        assertThrows(ServiceException.class, () -> service.verify("ticket"));
        p.setApiKey("fixture-key");
        p.setVerifyUrl("file:///tmp/{ticket}");
        assertThrows(ServiceException.class, () -> service.verify("ticket"));
        p.setVerifyUrl("http://{ticket}/verify");
        assertThrows(ServiceException.class, () -> service.verify("evil.invalid"));
        assertThrows(ServiceException.class, () -> service.verify(" "));
    }

    @Test void usesExistingOaHeadersAndRejectsExpiredRedirectAndOversizedResponse() throws Exception {
        var status = new AtomicInteger(200);
        var body = new AtomicReference<>("fixture.approver");
        var query = new AtomicReference<String>();
        var headers = new AtomicReference<com.sun.net.httpserver.Headers>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/verify", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            headers.set(exchange.getRequestHeaders());
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Location", "/verify");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var p = new OaProperties();p.setEnabled(true);p.setAppId("fixture-app");p.setApiKey("fixture-key");
            p.setVerifyUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/verify?ticket={ticket}");
            var service = new OaIdentityService(p);
            assertEquals("fixture.approver", service.verify("a+b&c=#"));
            assertEquals("ticket=a%2Bb%26c%3D%23", query.get());
            assertEquals("fixture-app", headers.get().getFirst("X-App-Id"));
            assertEquals("fixture-key", headers.get().getFirst("apikey"));
            assertTrue(headers.get().getFirst("X-Sequence-No").matches("[a-f0-9]{32}"));
            assertTrue(headers.get().getFirst("X-Timestamp").matches("[0-9]{14}"));
            for (int code : new int[]{401, 403, 302, 500}) {
                status.set(code);
                assertThrows(ServiceException.class, () -> service.verify("expired-or-used"));
            }
            status.set(200);body.set("x".repeat(1025));
            assertThrows(ServiceException.class, () -> service.verify("large"));
        } finally { server.stop(0); }
    }
}
