package com.ccr.admin.mobile;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 显式执行 ./dev mobile-api-test。使用 ccrsys-test 的 MySQL/Redis，真实 HTTP、鉴权和业务服务；
 * 仅外部 OA/有度验票由回环 HTTP 夹具代替。新增虚构机构和业务记录，结束时按机构精确清理。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "spring.profiles.active=dev",
        "spring.datasource.url=jdbc:mysql://127.0.0.1:23306/ccr_rate?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
        "spring.datasource.username=root", "spring.datasource.password=root123",
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=26379", "spring.data.redis.database=15",
        "ccr.integration.auth.enabled=false", "ccr.integration.auth.code-enabled=false",
        "ccr.integration.credit-resolution.enabled=false", "ccr.integration.wechat.enabled=false",
        "ccr.cache.enabled=false", "ccr.cache.config.refresh-cron=-", "ccr.cache.data.refresh-cron=-",
        "ccr.outbox.scan-cron=-", "ccr.message.retry-cron=-", "ccr.vote.timeout-scan-cron=-",
        "ccr.run-log.clean-cron=-", "ccr.audit.clean-cron=-",
        "logging.level.org.springframework.jdbc.core.JdbcTemplate=OFF",
        "logging.level.org.springframework.jdbc.core.StatementCreatorUtils=OFF", "logging.level.CCR_SQL=OFF"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MobileApiIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> TICKETS = new ConcurrentHashMap<>();
    private static final HttpServer PROVIDER = provider();
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String prefix = "mobile-it-" + UUID.randomUUID().toString().substring(0, 8);
    private long sequence = 8_100_000_000_000_000_000L + new Random().nextInt(1_000_000) * 10000L;
    private long org;
    private boolean fixtureOrgCreated;
    private String orgCode;
    private final Map<String, User> users = new LinkedHashMap<>();
    private final List<Long> configIds = new ArrayList<>();
    private final List<Long> applicationIds = new ArrayList<>();
    private final List<Long> fixtureUsers = new ArrayList<>();
    private final String password = UUID.randomUUID() + "Aa!";
    record User(long id, String account) {}
    record Application(long id, List<Long> items) {}

    @DynamicPropertySource static void identityProperties(DynamicPropertyRegistry r) {
        String url = "http://127.0.0.1:" + PROVIDER.getAddress().getPort();
        r.add("ccr.mobile.youdu.enabled", () -> true);
        r.add("ccr.mobile.youdu.verify-url", () -> url + "/youdu?token={token}");
        r.add("ccr.mobile.oa.enabled", () -> true);
        r.add("ccr.mobile.oa.verify-url", () -> url + "/oa?ticket={ticket}");
        r.add("ccr.mobile.oa.app-id", () -> "fixture-app");
        r.add("ccr.mobile.oa.api-key", () -> "fixture-key");
    }

    private static HttpServer provider() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String query = Objects.toString(exchange.getRequestURI().getRawQuery(), "");
                String ticket = URLDecoder.decode(query.substring(query.indexOf('=') + 1), StandardCharsets.UTF_8);
                String account = TICKETS.remove(ticket);
                boolean oa = exchange.getRequestURI().getPath().equals("/oa");
                if (oa && (!"fixture-app".equals(exchange.getRequestHeaders().getFirst("X-App-Id"))
                        || !"fixture-key".equals(exchange.getRequestHeaders().getFirst("apikey")))) account = null;
                byte[] bytes = (account == null ? "expired" : oa ? account
                        : JSON.writeValueAsString(Map.of("status", Map.of("code", 0), "userInfo", Map.of("account", account))))
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(account == null ? 401 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    @BeforeAll void fixtures() throws Exception {
        // 固定隔离栈连接；禁止通过环境覆盖为其他数据库后继续写入夹具。
        try (var c = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            assertTrue(c.getMetaData().getURL().startsWith("jdbc:mysql://127.0.0.1:23306/ccr_rate?"));
        }
        org = ++sequence;
        orgCode = "9" + Long.toString(org).substring(5);
        insert("ccr_sys_dept", Map.of("id", org, "org_code", orgCode, "branch_code", orgCode,
                "dept_name", "移动接口虚构测试机构", "org_type", "BRANCH"));
        fixtureOrgCreated = true;
        for (String role : List.of("branch_manager", "dept_gm", "vice_president", "secretary", "president", "customer_manager", "admin", "auditor", "contract_operator"))
            addUser(role, role);
        for (int i = 0; i < 6; i++) addUser("committee" + i, "committee_member");
        for (var entry : Map.of("DEPT_GENERAL_MANAGER", "dept_gm", "VICE_PRESIDENT", "vice_president", "SECRETARY", "secretary", "PRESIDENT", "president").entrySet()) {
            long id = ++sequence;
            insert("ccr_node_assignee", Map.of("id", id, "node_code", entry.getKey(), "assignee_type", "PERSON", "assignee_code", users.get(entry.getValue()).account()));
            configIds.add(id);
        }
    }

    private void addUser(String key, String role) {
        User user = new User(++sequence, prefix + "-" + key);
        insert("ccr_sys_user", Map.of("id", user.id(), "username", user.account(), "nick_name", "接口测试-" + key,
                "password", BCrypt.hashpw(password, BCrypt.gensalt(4)), "role_code", role, "org_id", org));
        users.put(key, user);
        fixtureUsers.add(user.id());
    }

    private void insert(String table, Map<String, ?> fields) {
        var keys = new ArrayList<>(fields.keySet());
        jdbc.update("INSERT INTO " + table + " (" + String.join(",", keys) + ") VALUES ("
                + String.join(",", Collections.nCopies(keys.size(), "?")) + ")", keys.stream().map(fields::get).toArray());
    }

    private long business(String table, Map<String, ?> fields) {
        long id = ++sequence;
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id); row.put("business_no", prefix + "-" + id); row.put("org_id", org); row.put("create_by", users.get("customer_manager").id());
        row.putAll(fields); insert(table, row); return id;
    }

    private Application application(String node, String type, int count) throws Exception {
        List<String> chain = new ArrayList<>(List.of(node));
        if (!"PRESIDENT".equals(node)) chain.add("PRESIDENT");
        String state = "SIX_PEOPLE_GROUP".equals(node) ? "VOTING" : "PRESIDENT".equals(node) ? "PRESIDENT_DECISION" : "ROUTING";
        var fields = new LinkedHashMap<String, Object>();
        fields.put("application_no", prefix + "-a-" + sequence % 10000); fields.put("business_type", type);
        fields.put("customer_scope", "CORPORATE_SINGLE"); fields.put("customer_no", prefix);
        fields.put("customer_info_json", JSON.writeValueAsString(Map.of("customerName", "移动接口虚构客户", "customerNo", prefix)));
        fields.put("applicant_user_id", users.get("customer_manager").id()); fields.put("applicant_org_id", org);
        fields.put("apply_branch_code", orgCode); fields.put("status", "PROCESSING"); fields.put("route_chain", JSON.writeValueAsString(chain));
        fields.put("current_node_code", node); fields.put("route_code", "PRESIDENT"); fields.put("dept_code", orgCode);
        long id = business("ccr_application", fields); applicationIds.add(id);
        List<Long> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            var item = new LinkedHashMap<String, Object>();
            item.put("application_id", id); item.put("pricing_item_no", prefix + "-i-" + sequence % 10000);
            item.put("pricing_customer_no", prefix); item.put("pricing_carrier_type", "DEPOSIT".equals(type) ? "DEPOSIT_ACCOUNT" : "LOAN_CONTRACT");
            item.put("product_code", "TEST"); item.put("term_value", 12); item.put("term_unit", "MONTH");
            item.put("pricing_amount", 100); item.put("requested_rate", 3.05); item.put("current_approval_rate", 3.15);
            item.put("rate_direction", "LOWER_BETTER"); item.put("status", state); item.put("current_node_code", node);
            item.put("route_chain", JSON.writeValueAsString(chain)); item.put("route_code", "PRESIDENT"); item.put("dept_code", orgCode);
            items.add(business("ccr_pricing_item", item));
        }
        return new Application(id, items);
    }

    private String login(String key, boolean oa) throws Exception {
        String ticket = UUID.randomUUID().toString(); TICKETS.put(ticket, users.get(key).account());
        JsonNode result = call(oa ? "/mobile/oa/login" : "/mobile/login", null, Map.of(oa ? "ticket" : "token", ticket), null);
        ok(result); assertTrue(result.path("data").path("userInfo").path("userId").isTextual(), "长 ID 必须按字符串传输");
        return result.path("data").path("token").asText();
    }

    private HttpResponse<byte[]> raw(String path, String token, Object body, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30));
        if (token != null) builder.header("Authorization", token);
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private JsonNode call(String path, String token, Object body, String key) throws Exception {
        return JSON.readTree(raw(path, token, body, key).body());
    }
    private void ok(JsonNode response) { assertEquals(200, response.path("code").asInt(), () -> "接口拒绝: " + response.path("code") + " " + response.path("msg").asText()); }
    private Map<String, Object> approval(Application a, String node, int version) { return new LinkedHashMap<>(Map.of("applicationId", Long.toString(a.id()), "nodeCode", node, "versionNo", version, "comment", "隔离接口回归意见")); }

    @Test void bothIdentitySourcesAllEligibleRolesAndLogout() throws Exception {
        for (String key : List.of("branch_manager", "dept_gm", "vice_president", "secretary", "committee0", "president")) {
            String token = login(key, true); ok(call("/mobile/session", token, null, null));
            ok(call("/mobile/tasks", token, null, null)); ok(call("/mobile/done?page=1&size=20", token, null, null));
            ok(call("/mobile/messages", token, null, null));
            assertEquals(403, call("/ccr/approval/tasks", token, null, null).path("code").asInt());
            ok(call("/mobile/logout", token, Map.of(), null));
            assertEquals(401, call("/mobile/session", token, null, null).path("code").asInt());
        }
        ok(call("/mobile/session", login("branch_manager", false), null, null));
    }

    @Test void identityRejectionsAndPcSessionIsolation() throws Exception {
        for (String role : List.of("customer_manager", "admin", "auditor", "contract_operator")) {
            String ticket = UUID.randomUUID().toString(); TICKETS.put(ticket, users.get(role).account());
            assertEquals(403, call("/mobile/oa/login", null, Map.of("ticket", ticket), null).path("code").asInt());
        }
        assertEquals(401, call("/mobile/oa/login", null, Map.of("ticket", "expired"), null).path("code").asInt());
        assertEquals(400, call("/mobile/login", null, Map.of("username", "admin"), null).path("code").asInt());
        JsonNode pc = call("/auth/login", null, Map.of("username", users.get("branch_manager").account(), "password", password), null); ok(pc);
        assertEquals(403, call("/mobile/session", pc.path("data").path("token").asText(), null, null).path("code").asInt());
    }

    @Test void disabledAccountCannotRestoreMobileSession() throws Exception {
        String token = login("branch_manager", true);
        jdbc.update("UPDATE ccr_sys_user SET status='DISABLE' WHERE id=?", users.get("branch_manager").id());
        try { assertEquals(401, call("/mobile/session", token, null, null).path("code").asInt()); }
        finally { jdbc.update("UPDATE ccr_sys_user SET status='ENABLE' WHERE id=?", users.get("branch_manager").id()); }
    }

    @Test void messagesAreBoundToCurrentUserEvenWithForgedRecipient() throws Exception {
        for (String role : List.of("branch_manager", "dept_gm")) business("ccr_notification_log", Map.of(
                "recipient_id", Long.toString(users.get(role).id()), "channel", "IN_APP", "message_content", prefix + role,
                "message_key", prefix + role, "retry_count", 0, "rule_version_id", 0L, "recipient_type", "USER", "send_status", "SUCCESS", "status", "SENT"));
        JsonNode messages = call("/mobile/messages?recipientId=" + users.get("dept_gm").id(), login("branch_manager", true), null, null); ok(messages);
        assertTrue(messages.path("data").toString().contains(prefix + "branch_manager"));
        assertFalse(messages.path("data").toString().contains(prefix + "dept_gm"));
    }

    @Test void detailAndAttachmentPreserveOwnershipBytesAndHeaders() throws Exception {
        Application a = application("BRANCH_MANAGER", "LOAN", 2), other = application("BRANCH_MANAGER", "LOAN", 1);
        byte[] pdf = "%PDF-1.4\nfixture\n%%EOF".getBytes(StandardCharsets.UTF_8);
        long file = business("ccr_application_attachment", Map.of("application_id", a.id(), "file_name", "测试附件.pdf", "file_size", pdf.length, "file_type", "application/pdf", "content", pdf));
        String token = login("branch_manager", true);
        JsonNode detail = call("/mobile/applications/" + a.id(), token, null, null); ok(detail);
        assertEquals(2, detail.path("data").path("siblingItems").size());
        assertEquals(Long.toString(file), detail.path("data").path("attachments").get(0).path("id").asText());
        var download = raw("/mobile/applications/" + a.id() + "/attachments/" + file, token, null, null);
        assertArrayEquals(pdf, download.body()); assertEquals("no-store", download.headers().firstValue("Cache-Control").orElse(""));
        assertTrue(download.headers().firstValue("Content-Disposition").orElse("").contains("attachment"));
        assertNotEquals(200, call("/mobile/applications/" + other.id() + "/attachments/" + file, token, null, null).path("code").asInt());
        jdbc.update("UPDATE ccr_application SET apply_branch_code='unrelated' WHERE id=?", other.id());
        assertEquals(403, call("/mobile/applications/" + other.id(), token, null, null).path("code").asInt());
        assertEquals(403, call("/mobile/applications/" + other.id() + "/attachments/" + file, token, null, null).path("code").asInt());
    }

    @Test void ordinaryApprovalRejectionDoneAndDuplicateRequests() throws Exception {
        for (String[] roleNode : List.of(new String[]{"branch_manager", "BRANCH_MANAGER"}, new String[]{"dept_gm", "DEPT_GENERAL_MANAGER"}, new String[]{"vice_president", "VICE_PRESIDENT"}, new String[]{"secretary", "SECRETARY"})) {
            Application a = application(roleNode[1], "LOAN", 2); String token = login(roleNode[0], true); String key = UUID.randomUUID().toString();
            ok(call("/mobile/approve", token, approval(a, roleNode[1], 1), key));
            assertNotEquals(200, call("/mobile/approve", token, approval(a, roleNode[1], 1), key).path("code").asInt());
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ccr_approval_action WHERE pricing_item_id IN (?,?) AND operation_channel='MOBILE'", Integer.class, a.items().get(0), a.items().get(1)));
            JsonNode done = call("/mobile/done?page=1&size=100", token, null, null); ok(done);
            assertTrue(done.path("data").path("rows").toString().contains(Long.toString(a.id())));
            ok(call("/mobile/applications/" + a.id(), token, null, null));
        }
        Application a = application("BRANCH_MANAGER", "DEPOSIT", 2); String token = login("branch_manager", true);
        ok(call("/mobile/reject", token, approval(a, "BRANCH_MANAGER", 1), UUID.randomUUID().toString()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ccr_pricing_item WHERE application_id=? AND status='REJECTED'", Integer.class, a.id()));
        JsonNode rejected = call("/mobile/applications/" + a.id(), token, null, null); ok(rejected);
        assertEquals("REJECTED", rejected.path("data").path("application").get(0).path("applicationStatus").asText());
        JsonNode trace = rejected.path("data").path("flowTrace");
        assertTrue(trace.isArray() && !trace.isEmpty());
        assertEquals("REJECT", trace.get(trace.size() - 1).path("actionType").asText());
        assertEquals("隔离接口回归意见", trace.get(trace.size() - 1).path("actionComment").asText());
    }

    @Test void staleVersionAndInvalidParametersDoNotWriteActions() throws Exception {
        Application a = application("BRANCH_MANAGER", "LOAN", 1); String token = login("branch_manager", true);
        var body = approval(a, "BRANCH_MANAGER", 0);
        assertEquals(1010, call("/mobile/approve", token, body, UUID.randomUUID().toString()).path("code").asInt());
        assertEquals(1010, call("/mobile/reject", token, body, UUID.randomUUID().toString()).path("code").asInt());
        body.put("versionNo", 1);
        assertEquals(400, call("/mobile/approve", token, body, null).path("code").asInt());
        body.put("rateAdjustments", Map.of(Long.toString(a.items().get(0)), 3.155));
        assertEquals(400, call("/mobile/approve", token, body, UUID.randomUUID().toString()).path("code").asInt());
        body.put("rateAdjustments", Map.of("1", 3.16));
        assertEquals(403, call("/mobile/approve", token, body, UUID.randomUUID().toString()).path("code").asInt());
        assertEquals(400, call("/mobile/done?page=0&size=101", token, null, null).path("code").asInt());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ccr_approval_action WHERE pricing_item_id=?", Integer.class, a.items().get(0)));
    }

    @Test void noAdjustmentPreservesRouteAndForbiddenAdjustmentsAreRejected() throws Exception {
        for (String[] pair : List.of(new String[]{"SECRETARY", "LOAN", "secretary"}, new String[]{"BRANCH_MANAGER", "DEPOSIT", "branch_manager"})) {
            Application a = application(pair[0], pair[1], 1); String token = login(pair[2], true);
            var body = approval(a, pair[0], 1); body.put("rateAdjustments", Map.of(Long.toString(a.items().get(0)), 3.16));
            assertEquals(403, call("/mobile/approve", token, body, UUID.randomUUID().toString()).path("code").asInt());
            assertEquals(1, jdbc.queryForObject("SELECT version_no FROM ccr_application WHERE id=?", Integer.class, a.id()));
        }
        Application a = application("BRANCH_MANAGER", "LOAN", 1); String token = login("branch_manager", true);
        var body = approval(a, "BRANCH_MANAGER", 1); body.put("rateAdjustments", Map.of());
        ok(call("/mobile/approve", token, body, UUID.randomUUID().toString()));
        assertEquals("PRESIDENT", jdbc.queryForObject("SELECT current_node_code FROM ccr_application WHERE id=?", String.class, a.id()));
        assertEquals(2, jdbc.queryForObject("SELECT version_no FROM ccr_application WHERE id=?", Integer.class, a.id()));
        Application unrelated = application("PRESIDENT", "LOAN", 1);
        assertEquals(403, call("/mobile/applications/" + unrelated.id(), login("secretary", true), null, null).path("code").asInt());
    }

    @Test void oneBpAdjustmentUsesActualMatrixAndKeepsOtherItemRate() throws Exception {
        String product = prefix;
        var matrix = new LinkedHashMap<String, Object>();
        matrix.put("matrix_no", prefix); matrix.put("business_big_type", "LOAN_PUBLIC"); matrix.put("new_or_existing", "NEW");
        matrix.put("product_code", product); matrix.put("start_node_code", "BRANCH_MANAGER"); matrix.put("dept_code", orgCode);
        matrix.put("boundary_type", "RATE"); matrix.put("boundary_min_rate", 0.1); matrix.put("priority", -1000);
        matrix.put("effective_from", LocalDateTime.now().minusDays(1));
        business("ccr_rate_matrix", matrix);
        business("ccr_product_route", Map.of("product_code", product, "business_big_type", "LOAN", "status", "PUBLISHED", "route_mode", "CHAINED", "effective_date", LocalDateTime.now().minusDays(1)));
        Application a = application("BRANCH_MANAGER", "LOAN", 2);
        jdbc.update("UPDATE ccr_pricing_item SET product_code=? WHERE application_id=?", product, a.id());
        var body = approval(a, "BRANCH_MANAGER", 1); body.put("rateAdjustments", Map.of(Long.toString(a.items().get(0)), 3.16));
        ok(call("/mobile/approve", login("branch_manager", true), body, UUID.randomUUID().toString()));
        assertEquals(0, new BigDecimal("3.16").compareTo(jdbc.queryForObject("SELECT current_approval_rate FROM ccr_pricing_item WHERE id=?", BigDecimal.class, a.items().get(0))));
        assertEquals(0, new BigDecimal("3.15").compareTo(jdbc.queryForObject("SELECT current_approval_rate FROM ccr_pricing_item WHERE id=?", BigDecimal.class, a.items().get(1))));
    }

    @Test void concurrentApprovalOnlyCommitsOnce() throws Exception {
        Application a = application("BRANCH_MANAGER", "LOAN", 2); String token = login("branch_manager", true);
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<JsonNode>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) futures.add(executor.submit(() -> { start.await(); return call("/mobile/approve", token, approval(a, "BRANCH_MANAGER", 1), UUID.randomUUID().toString()); }));
            start.countDown(); int success = 0;
            for (var future : futures) { int code = future.get().path("code").asInt(); if (code == 200) success++; else assertEquals(1010, code); }
            assertEquals(1, success);
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ccr_approval_action WHERE pricing_item_id IN (?,?)", Integer.class, a.items().get(0), a.items().get(1)));
        } finally { executor.shutdownNow(); }
    }

    @Test void depositApprovalCreatesRoundAndRejectVotesEndWholeApplication() throws Exception {
        Application a = application("BRANCH_MANAGER", "DEPOSIT", 2);
        ok(call("/mobile/approve", login("branch_manager", true), approval(a, "BRANCH_MANAGER", 1), UUID.randomUUID().toString()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ccr_pricing_item WHERE application_id=? AND status='VOTING'", Integer.class, a.id()));
        long round = jdbc.queryForObject("SELECT id FROM ccr_vote_round WHERE application_id=?", Long.class, a.id());
        // 种子配置冻结的委员名单只用于证明建批；将本测试批次席位精确替换成六个虚构测试账号。
        jdbc.update("DELETE FROM ccr_vote_assignment WHERE round_id=?", round);
        for (int i = 0; i < 6; i++) business("ccr_vote_assignment", Map.of("round_id", round, "voter_user_id", users.get("committee" + i).id(), "voter_anonym_no", "T" + i));
        for (int i = 0; i < 6; i++) {
            String token = login("committee" + i, true);
            var ballot = Map.of("applicationId", Long.toString(a.id()), "choice", "REJECT", "comment", "隔离表决否决原因");
            ok(call("/mobile/vote-rounds/" + round + "/ballots", token, ballot, UUID.randomUUID().toString()));
        }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ccr_pricing_item WHERE application_id=? AND status='REJECTED'", Integer.class, a.id()));
    }

    @Test void votingAndPresidentDecisionUseRealTransactionsAndConcealOtherBallots() throws Exception {
        Application a = application("SIX_PEOPLE_GROUP", "LOAN", 2);
        long round = business("ccr_vote_round", Map.of("application_id", a.id(), "round_no", 1, "status", "VOTING"));
        for (long item : a.items()) business("ccr_vote_round_item", Map.of("round_id", round, "pricing_item_id", item));
        for (int i = 0; i < 6; i++) business("ccr_vote_assignment", Map.of("round_id", round, "voter_user_id", users.get("committee" + i).id(), "voter_anonym_no", "T" + i));
        String path = "/mobile/vote-rounds/" + round + "/ballots";
        var ballot = Map.of("applicationId", Long.toString(a.id()), "choice", "APPROVE", "comment", "虚构表决意见");
        assertNotEquals(200, call(path, login("branch_manager", true), ballot, UUID.randomUUID().toString()).path("code").asInt());
        for (int i = 0; i < 6; i++) {
            String token = login("committee" + i, true), key = UUID.randomUUID().toString();
            JsonNode tasks = call("/mobile/tasks", token, null, null); ok(tasks);
            assertTrue(tasks.path("data").path("vote").findValues("currentApprovalRate").stream().anyMatch(rate -> rate.decimalValue().compareTo(new BigDecimal("3.15")) == 0));
            ok(call(path, token, ballot, key));
            assertNotEquals(200, call(path, token, ballot, key).path("code").asInt());
            JsonNode detail = call("/mobile/applications/" + a.id(), token, null, null); ok(detail);
            assertFalse(detail.path("data").has("voteResults")); assertFalse(detail.path("data").path("voteRound").has("approveCount"));
            assertEquals("APPROVE", detail.path("data").path("voteRound").path("myChoice").asText());
            assertEquals(403, call("/mobile/vote-rounds/" + round + "/opinions", token, null, null).path("code").asInt());
            ok(call("/mobile/done", token, null, null));
        }
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM ccr_ballot WHERE round_id=?", Integer.class, round));
        String president = login("president", true);
        ok(call("/mobile/tasks", president, null, null));
        ok(call("/mobile/vote-rounds/" + round + "/opinions", president, null, null));
        for (String decision : List.of("APPROVE", "VETO")) {
            Application target = decision.equals("APPROVE") ? a : application("PRESIDENT", "LOAN", 2);
            var command = Map.of("applicationId", Long.toString(target.id()), "decision", decision, "opinion", "虚构行长意见");
            ok(call("/mobile/decisions", president, command, UUID.randomUUID().toString()));
            assertNotEquals(200, call("/mobile/decisions", president, command, UUID.randomUUID().toString()).path("code").asInt());
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ccr_president_decision WHERE pricing_item_id IN (?,?)", Integer.class, target.items().get(0), target.items().get(1)));
        }
    }

    @AfterAll void cleanup() {
        try {
            if (!fixtureOrgCreated) return;
            for (User user : users.values()) StpUtil.logout(user.id());
            for (long id : configIds) jdbc.update("DELETE FROM ccr_node_assignee WHERE id=?", id);
            var flowChildren = jdbc.queryForList("SELECT table_name FROM information_schema.columns WHERE table_schema=DATABASE() AND column_name='instance_id' AND table_name LIKE 'flow_%'", String.class);
            for (long id : applicationIds) {
                jdbc.update("DELETE FROM ccr_outbox_event WHERE JSON_UNQUOTE(JSON_EXTRACT(payload,'$.applicationId'))=?", Long.toString(id));
                List<String> keys = new ArrayList<>(List.of(Long.toString(id)));
                keys.addAll(jdbc.queryForList("SELECT application_no FROM ccr_application WHERE id=?", String.class, id));
                keys.addAll(jdbc.queryForList("SELECT pricing_item_no FROM ccr_pricing_item WHERE application_id=?", String.class, id));
                for (String key : keys) for (long instance : jdbc.queryForList("SELECT id FROM flow_instance WHERE business_id=?", Long.class, key)) {
                    for (String table : flowChildren) jdbc.update("DELETE FROM `" + table + "` WHERE instance_id=?", instance);
                    jdbc.update("DELETE FROM flow_instance WHERE id=?", instance);
                }
            }
            for (long id : fixtureUsers) jdbc.update("DELETE FROM ccr_audit_log WHERE operator_id=?", id);
            // 仅删除本次新建机构的数据，不清库、不重建卷；表名来自数据库元数据。
            for (String table : jdbc.queryForList("SELECT table_name FROM information_schema.columns WHERE table_schema=DATABASE() AND column_name='org_id' AND table_name LIKE 'ccr_%'", String.class))
                jdbc.update("DELETE FROM `" + table + "` WHERE org_id=?", org);
            jdbc.update("DELETE FROM ccr_sys_dept WHERE id=?", org);
        } finally { TICKETS.clear(); PROVIDER.stop(0); }
    }
}
