package dev.tcpprobe.service;

import dev.tcpprobe.ProbeProperties;
import dev.tcpprobe.probe.ProbeStatus;
import dev.tcpprobe.probe.ProbeTarget;
import dev.tcpprobe.probe.TcpProber;
import dev.tcpprobe.web.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProbeServiceTest {

    private ProbeService service;

    @AfterEach
    void shutdown() {
        if (service != null) {
            service.shutdown();
        }
    }

    private static ProbeProperties properties(int maxTargets, int maxConcurrentTasks, int workers, int maxInFlight) {
        return new ProbeProperties(400, 200, maxInFlight, maxTargets, maxConcurrentTasks, workers,
                60, 5000, 1048576, new ProbeProperties.RateLimit(1000, 1000, 1000, 1000));
    }

    private ProbeService newService(ProbeProperties properties) {
        TcpProber prober = new TcpProber(properties.timeoutMs(), properties.dnsTimeoutMs(), 2);
        return new ProbeService(properties, prober, new RateLimiter(properties));
    }

    @Test
    void keepsResultsInRequestOrderAndSummarisesThem() throws IOException {
        try (ServerSocket listener = new ServerSocket(0)) {
            int openPort = listener.getLocalPort();
            int closedPort = freePort();
            service = newService(properties(10, 4, 2, 4));

            ProbeTask task = service.submit(List.of(
                    ProbeTarget.parse("127.0.0.1:" + openPort),
                    ProbeTarget.parse("127.0.0.1:" + closedPort)), 500, ProbeTask.Mode.ASYNC);
            task.await(5000);

            assertThat(task.state()).isEqualTo(ProbeTask.State.SUCCEEDED);
            assertThat(task.results()).extracting(result -> result.target().port())
                    .containsExactly(openPort, closedPort);
            assertThat(task.results()).extracting(result -> result.status())
                    .containsExactly(ProbeStatus.CONNECTED, ProbeStatus.REFUSED);

            Map<String, Object> summary = task.summary();
            assertThat(summary).containsEntry("connected", 1).containsEntry("refused", 1);
            assertThat(summary.get("averageLatencyMs")).isNotNull();
        }
    }

    @Test
    void rejectsEmptyAndOversizedBatches() {
        service = newService(properties(2, 4, 2, 4));

        assertThatThrownBy(() -> service.submit(List.of(), 500, ProbeTask.Mode.ASYNC))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("EMPTY_TARGETS"));

        assertThatThrownBy(() -> service.submit(List.of(
                ProbeTarget.parse("a.example:1"),
                ProbeTarget.parse("b.example:2"),
                ProbeTarget.parse("c.example:3")), 500, ProbeTask.Mode.ASYNC))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("TOO_MANY_TARGETS"));
    }

    @Test
    void returnsServerBusyWhenEveryTaskSlotIsTaken() {
        // 一个槽位 + 一个不会立刻返回的目标，第二个提交必然被拒。
        service = newService(properties(10, 1, 1, 1));
        service.submit(List.of(ProbeTarget.parse("192.0.2.1:9")), 2000, ProbeTask.Mode.ASYNC);

        assertThatThrownBy(() -> service.submit(List.of(ProbeTarget.parse("127.0.0.1:1")), 500,
                ProbeTask.Mode.ASYNC))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(503);
                    assertThat(e.code()).isEqualTo("SERVER_BUSY");
                });
    }

    @Test
    void storesTasksAndCountsProbes() throws IOException {
        try (ServerSocket listener = new ServerSocket(0)) {
            service = newService(properties(10, 4, 2, 4));
            ProbeTask task = service.submit(List.of(ProbeTarget.parse("127.0.0.1:" + listener.getLocalPort())),
                    500, ProbeTask.Mode.ASYNC);
            task.await(5000);

            assertThat(service.find(task.id())).containsSame(task);
            assertThat(service.find("p_missing")).isEmpty();

            Map<String, Object> stats = service.stats();
            Map<?, ?> tasks = (Map<?, ?>) stats.get("tasks");
            Map<?, ?> probes = (Map<?, ?>) stats.get("probes");
            assertThat(tasks.get("succeeded")).isEqualTo(1L);
            assertThat(probes.get("attempted")).isEqualTo(1L);
            assertThat(probes.get("connected")).isEqualTo(1L);
        }
    }

    @Test
    void evictsFinishedTasksOlderThanTtl() throws Exception {
        service = newService(new ProbeProperties(400, 200, 2, 10, 4, 2, 0, 5000, 1048576,
                new ProbeProperties.RateLimit(1000, 1000, 1000, 1000)));
        ProbeTask task = service.submit(List.of(ProbeTarget.parse("127.0.0.1:1")), 300, ProbeTask.Mode.ASYNC);
        task.await(5000);

        Thread.sleep(20);
        service.evictExpired();

        assertThat(service.find(task.id())).isEmpty();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
