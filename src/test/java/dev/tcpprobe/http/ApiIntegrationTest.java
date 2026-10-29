package dev.tcpprobe.http;

import dev.tcpprobe.config.Config;
import dev.tcpprobe.json.Json;
import dev.tcpprobe.json.JsonValue;
import dev.tcpprobe.probe.TcpProber;
import dev.tcpprobe.service.Metrics;
import dev.tcpprobe.service.ProbeService;
import dev.tcpprobe.service.RateLimiter;
import dev.tcpprobe.service.TaskRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 基于真实 socket 的端到端测试：这是唯一能证明路由、JSON 与 HTTP 状态码彼此一致的办法。 */
class ApiIntegrationTest {

    private Harness harness;

    @BeforeEach
    void setUp() throws IOException {
        harness = new Harness(config(1000.0, 1000, 1000, 1000));
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    private static Config config(double globalRps, int globalBurst, double clientRps, int clientBurst) {
        return new Config("127.0.0.1", 0, 400, 400, 8, 50, 4, 2, 60,
                globalRps, globalBurst, clientRps, clientBurst, 6000, 65_536, false, "test");
    }

    @Test
    void asyncSubmitThenPoll() throws Exception {
        HttpResponse<String> submit = harness.post("/api/v1/probes", harness.defaultBody(), "application/json");
        assertTrue(submit.statusCode() == 202 || submit.statusCode() == 200,
                "expected 202 (pending) or 200 (already done), got " + submit.statusCode() + ": " + submit.body());
        JsonValue.Obj submitted = obj(submit);
        String taskId = submitted.get("taskId").asString();
        assertNotNull(taskId);
        assertTrue(submit.headers().firstValue("Location").orElse("").endsWith(taskId));

        HttpResponse<String> poll = harness.get("/api/v1/probes/" + taskId + "?waitMs=4000");
        assertEquals(200, poll.statusCode(), poll.body());
        JsonValue.Obj finished = obj(poll);
        assertEquals("SUCCEEDED", finished.get("status").asString());
        assertEquals("async", finished.get("mode").asString());
        JsonValue.Arr results = finished.get("results").asArr();
        assertEquals(2, results.size());
        assertEquals("CONNECTED", results.values().get(0).asObj().get("status").asString());
        assertEquals("REFUSED", results.values().get(1).asObj().get("status").asString());
        assertEquals(1L, finished.get("summary").asObj().get("connected").asLong());
        assertEquals(1L, finished.get("summary").asObj().get("refused").asLong());
    }

    @Test
    void syncModeReturnsResultsInline() throws Exception {
        String body = "{\"targets\":[\"127.0.0.1:" + harness.listenerPort() + "\"],\"timeoutMs\":400,\"mode\":\"sync\"}";
        HttpResponse<String> response = harness.post("/api/v1/probes", body, "application/json");
        assertEquals(200, response.statusCode(), response.body());
        JsonValue.Obj task = obj(response);
        assertEquals("SUCCEEDED", task.get("status").asString());
        assertEquals("sync", task.get("mode").asString());
        assertEquals("CONNECTED",
                task.get("results").asArr().values().get(0).asObj().get("status").asString());
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        HttpResponse<String> response = harness.post("/api/v1/probes", "{\"targets\": [", "application/json");
        assertEquals(400, response.statusCode(), response.body());
        assertEquals("MALFORMED_JSON", errorCode(response));
    }

    @Test
    void rejectsInvalidTargetsWithDetails() throws Exception {
        HttpResponse<String> response = harness.post("/api/v1/probes",
                "{\"targets\":[\"nonsense\",\"ok.example.com:443\"]}", "application/json");
        assertEquals(400, response.statusCode(), response.body());
        JsonValue.Obj error = obj(response).get("error").asObj();
        assertEquals("INVALID_TARGETS", error.get("code").asString());
        assertEquals(1, error.get("details").asArr().size());
        assertTrue(error.get("details").asArr().values().get(0).asString().startsWith("targets[0]"));
    }

    @Test
    void rejectsEmptyTargetList() throws Exception {
        HttpResponse<String> response = harness.post("/api/v1/probes", "{\"targets\":[]}", "application/json");
        assertEquals(400, response.statusCode(), response.body());
        assertEquals("EMPTY_TARGETS", errorCode(response));
    }

    @Test
    void rejectsWrongContentType() throws Exception {
        HttpResponse<String> response = harness.post("/api/v1/probes", harness.defaultBody(), "text/plain");
        assertEquals(415, response.statusCode(), response.body());
    }

    @Test
    void rejectsOversizedBodyWith413() throws Exception {
        String body = "{\"targets\":[\"127.0.0.1:1\"],\"pad\":\"" + "x".repeat(70_000) + "\"}";
        HttpResponse<String> response = harness.post("/api/v1/probes", body, "application/json");
        assertEquals(413, response.statusCode(), response.body());
        assertEquals("PAYLOAD_TOO_LARGE", errorCode(response));
    }

    @Test
    void rejectsBadQueryParameterWith400() throws Exception {
        HttpResponse<String> response = harness.get("/api/v1/probes/p_missing?waitMs=soon");
        assertEquals(400, response.statusCode(), response.body());
        assertEquals("INVALID_QUERY", errorCode(response));
    }

    @Test
    void unknownTaskIs404() throws Exception {
        HttpResponse<String> response = harness.get("/api/v1/probes/p_missing");
        assertEquals(404, response.statusCode(), response.body());
        assertEquals("NOT_FOUND", errorCode(response));
    }

    @Test
    void unknownRouteIs404() throws Exception {
        assertEquals(404, harness.get("/nope").statusCode());
    }

    @Test
    void wrongMethodIs405WithAllowHeader() throws Exception {
        HttpResponse<String> response = harness.send("DELETE", "/api/v1/probes/p_x", null);
        assertEquals(405, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Allow").orElse("").contains("GET"));
    }

    @Test
    void healthStatsAndRootAreServed() throws Exception {
        HttpResponse<String> health = harness.get("/healthz");
        assertEquals(200, health.statusCode());
        assertEquals("ok", obj(health).get("status").asString());

        HttpResponse<String> root = harness.get("/");
        assertEquals(200, root.statusCode());
        assertTrue(root.body().contains("POST /api/v1/probes"));

        HttpResponse<String> stats = harness.get("/api/v1/stats");
        assertEquals(200, stats.statusCode());
        assertTrue(obj(stats).get("http").asObj().get("requests").asLong() >= 3);
    }

    @Test
    void rateLimitsPerClientWithRetryAfter() throws IOException {
        Config limited = config(1000.0, 1000, 0.001, 1);
        try (Harness tight = new Harness(limited)) {
            HttpResponse<String> first = tight.post("/api/v1/probes", tight.defaultBody(), "application/json");
            assertTrue(first.statusCode() == 200 || first.statusCode() == 202, first.body());

            HttpResponse<String> second = tight.post("/api/v1/probes", tight.defaultBody(), "application/json");
            assertEquals(429, second.statusCode(), second.body());
            assertEquals("RATE_LIMITED", errorCode(second));
            assertTrue(second.headers().firstValue("Retry-After").isPresent());
            assertEquals("0", second.headers().firstValue("X-RateLimit-Remaining").orElse("?"));
        }
    }

    private static JsonValue.Obj obj(HttpResponse<String> response) {
        return Json.parse(response.body()).asObj();
    }

    private static String errorCode(HttpResponse<String> response) {
        return obj(response).get("error").asObj().get("code").asString();
    }

    /** 启动一个服务器，外加一个始终接受连接的本地 listener，使 CONNECTED 可复现。 */
    private static final class Harness implements AutoCloseable {

        private final ServerSocket listener;
        private final int closedPort;
        private final ProbeService service;
        private final ProbeHttpServer server;
        private final HttpClient client;
        private final String base;

        Harness(Config config) throws IOException {
            listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(() -> {
                while (!listener.isClosed()) {
                    try (Socket accepted = listener.accept()) {
                        // 握手足够
                        accepted.getRemoteSocketAddress();
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            try (ServerSocket ephemeral = new ServerSocket(0)) {
                closedPort = ephemeral.getLocalPort();
            }

            Metrics metrics = new Metrics();
            service = new ProbeService(config, new TcpProber(config.probeTimeoutMs()), new TaskRegistry(100), metrics);
            server = new ProbeHttpServer(config, service,
                    new RateLimiter(config.globalRateRps(), config.globalRateBurst(),
                            config.clientRateRps(), config.clientRateBurst()),
                    metrics).start();
            client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            base = "http://127.0.0.1:" + server.port();
        }

        int listenerPort() {
            return listener.getLocalPort();
        }

        String defaultBody() {
            return "{\"targets\":[\"127.0.0.1:" + listenerPort() + "\",\"127.0.0.1:" + closedPort
                    + "\"],\"timeoutMs\":400}";
        }

        HttpResponse<String> post(String path, String body, String contentType) throws IOException {
            try {
                return send("POST", path, body, contentType);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }

        HttpResponse<String> get(String path) throws IOException {
            try {
                return send("GET", path, null, null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }

        HttpResponse<String> send(String method, String path, String body) throws IOException, InterruptedException {
            return send(method, path, body, "application/json");
        }

        HttpResponse<String> send(String method, String path, String body, String contentType)
                throws IOException, InterruptedException {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(15));
            if (body != null) {
                builder.header("Content-Type", contentType == null ? "application/json" : contentType);
                builder.method(method, HttpRequest.BodyPublishers.ofString(body));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public void close() {
            server.stop(0);
            service.shutdown(Duration.ofMillis(300));
            try {
                listener.close();
            } catch (IOException ignored) {
                // 无需处理
            }
        }
    }
}
