package dev.tcpprobe.web;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端测试：真启动一个 Spring 容器，用 HTTP 打进来，目标里有一个真开的端口和一个关掉的端口。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "probe.timeout-ms=500",
        "probe.dns-timeout-ms=300",
        "probe.max-targets-per-request=5",
        "probe.rate-limit.client.rps=10000",
        "probe.rate-limit.client.burst=10000"
})
class ProbeApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON =
            new ParameterizedTypeReference<>() {
            };

    private static ServerSocket listener;
    private static int openPort;
    private static int closedPort;

    @Autowired
    private TestRestTemplate rest;

    @BeforeAll
    static void openSockets() throws IOException {
        listener = new ServerSocket(0);
        openPort = listener.getLocalPort();
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
    }

    @AfterAll
    static void closeSockets() throws IOException {
        listener.close();
    }

    @Test
    void runsSyncBatchInline() {
        ResponseEntity<Map<String, Object>> response = post("""
                {"mode":"sync","targets":["127.0.0.1:%d","127.0.0.1:%d"]}
                """.formatted(openPort, closedPort));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION)).startsWith("/api/v1/probes/");

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo("SUCCEEDED");
        assertThat(body.get("mode")).isEqualTo("sync");
        assertThat(body.get("targetCount")).isEqualTo(2);

        List<?> results = (List<?>) body.get("results");
        assertThat(results).hasSize(2);
        assertThat(((Map<?, ?>) results.get(0)).get("status")).isEqualTo("CONNECTED");
        assertThat(((Map<?, ?>) results.get(1)).get("status")).isEqualTo("REFUSED");

        Map<?, ?> summary = (Map<?, ?>) body.get("summary");
        assertThat(summary.get("connected")).isEqualTo(1);
        assertThat(summary.get("refused")).isEqualTo(1);
    }

    @Test
    void returnsPendingForAsyncThenCompletesOnLongPoll() {
        ResponseEntity<Map<String, Object>> accepted = post("""
                {"targets":["127.0.0.1:%d"]}
                """.formatted(openPort));

        assertThat(accepted.getStatusCode().value()).isIn(200, 202);
        Map<String, Object> body = accepted.getBody();
        assertThat(body).isNotNull();
        String taskId = (String) body.get("taskId");
        assertThat(taskId).startsWith("p_");
        assertThat(body.get("mode")).isEqualTo("async");

        ResponseEntity<Map<String, Object>> polled =
                rest.exchange("/api/v1/probes/" + taskId + "?waitMs=5000", HttpMethod.GET, null, JSON);

        assertThat(polled.getStatusCode().value()).isEqualTo(200);
        assertThat(polled.getBody().get("status")).isEqualTo("SUCCEEDED");
        assertThat((List<?>) polled.getBody().get("results")).hasSize(1);
    }

    @Test
    void rejectsMalformedJson() {
        ResponseEntity<Map<String, Object>> response = post("{\"targets\":[");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(errorCode(response)).isEqualTo("MALFORMED_JSON");
    }

    @Test
    void reportsEveryInvalidTargetWithItsIndex() {
        ResponseEntity<Map<String, Object>> response = post("""
                {"targets":["nope:0","127.0.0.1:%d"]}
                """.formatted(openPort));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(errorCode(response)).isEqualTo("INVALID_TARGETS");
        List<?> details = (List<?>) error(response).get("details");
        assertThat(details).hasSize(1);
        assertThat((String) details.get(0)).startsWith("targets[0]");
    }

    @Test
    void rejectsEmptyTargetList() {
        ResponseEntity<Map<String, Object>> response = post("{\"targets\":[]}");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(errorCode(response)).isEqualTo("EMPTY_TARGETS");
    }

    @Test
    void rejectsTooManyTargets() {
        ResponseEntity<Map<String, Object>> response = post("""
                {"targets":["a.example:1","b.example:1","c.example:1","d.example:1","e.example:1","f.example:1"]}
                """);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(errorCode(response)).isEqualTo("TOO_MANY_TARGETS");
    }

    @Test
    void rejectsNonJsonContentType() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        ResponseEntity<Map<String, Object>> response = rest.exchange("/api/v1/probes", HttpMethod.POST,
                new HttpEntity<>("127.0.0.1:1", headers), JSON);

        assertThat(response.getStatusCode().value()).isEqualTo(415);
    }

    @Test
    void rejectsOversizedBody() {
        String padding = "a".repeat(1_100_000);
        ResponseEntity<Map<String, Object>> response =
                post("{\"targets\":[\"127.0.0.1:1\"],\"pad\":\"" + padding + "\"}");

        assertThat(response.getStatusCode().value()).isEqualTo(413);
    }

    @Test
    void returnsNotFoundForUnknownTask() {
        ResponseEntity<Map<String, Object>> response =
                rest.exchange("/api/v1/probes/p_doesnotexist", HttpMethod.GET, null, JSON);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(errorCode(response)).isEqualTo("NOT_FOUND");
    }

    @Test
    void returnsNotFoundForUnknownRoute() {
        ResponseEntity<Map<String, Object>> response =
                rest.exchange("/nope", HttpMethod.GET, null, JSON);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void returnsMethodNotAllowedWithAllowHeader() {
        ResponseEntity<Map<String, Object>> response =
                rest.exchange("/api/v1/probes", HttpMethod.DELETE, null, JSON);

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ALLOW)).contains("POST");
    }

    @Test
    void rejectsBadQueryParameter() {
        ResponseEntity<Map<String, Object>> response =
                rest.exchange("/api/v1/probes/p_x?waitMs=soon", HttpMethod.GET, null, JSON);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(errorCode(response)).isEqualTo("INVALID_QUERY");
    }

    @Test
    void exposesHealthAndStats() {
        ResponseEntity<Map<String, Object>> health =
                rest.exchange("/healthz", HttpMethod.GET, null, JSON);
        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(health.getBody().get("status")).isEqualTo("ok");

        ResponseEntity<Map<String, Object>> stats =
                rest.exchange("/api/v1/stats", HttpMethod.GET, null, JSON);
        assertThat(stats.getStatusCode().value()).isEqualTo(200);
        assertThat(stats.getBody()).containsKeys("tasks", "probes", "rateLimit", "rateLimitedClients");
    }

    private ResponseEntity<Map<String, Object>> post(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/api/v1/probes", HttpMethod.POST, new HttpEntity<>(body, headers), JSON);
    }

    private static Map<?, ?> error(ResponseEntity<Map<String, Object>> response) {
        return (Map<?, ?>) response.getBody().get("error");
    }

    private static Object errorCode(ResponseEntity<Map<String, Object>> response) {
        return error(response).get("code");
    }
}
