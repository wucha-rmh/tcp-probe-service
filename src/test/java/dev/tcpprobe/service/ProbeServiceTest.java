package dev.tcpprobe.service;

import dev.tcpprobe.config.Config;
import dev.tcpprobe.error.ApiException;
import dev.tcpprobe.probe.ProbeResult;
import dev.tcpprobe.probe.ProbeStatus;
import dev.tcpprobe.probe.ProbeTarget;
import dev.tcpprobe.probe.TcpProber;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeServiceTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private ServerSocket listener;
    private int closedPort;
    private ProbeService service;

    @BeforeEach
    void setUp() throws IOException {
        listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            while (!listener.isClosed()) {
                try (Socket accepted = listener.accept()) {
                    // 探测只需要握手完成
                    accepted.getRemoteSocketAddress();                } catch (IOException e) {
                    return;
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        try (ServerSocket ephemeral = new ServerSocket(0)) {
            closedPort = ephemeral.getLocalPort();
        }
        service = new ProbeService(config(), new TcpProber(400), new TaskRegistry(100), new Metrics());
    }

    @AfterEach
    void tearDown() throws IOException {
        service.shutdown(Duration.ofMillis(500));
        listener.close();
    }

    private static Config config() {
        return new Config("127.0.0.1", 0, 400, 400, 8, 50, 4, 2, 60,
                1000.0, 1000, 1000.0, 1000, 6000, 65_536, false, "test");
    }

    @Test
    void executesBatchPreservingOrder() throws Exception {
        List<ProbeTarget> targets = List.of(
                ProbeTarget.parse("127.0.0.1:" + listener.getLocalPort()),
                ProbeTarget.parse("127.0.0.1:" + closedPort));
        ProbeTask task = service.submit(targets, 400, ProbeTask.Mode.ASYNC);

        task.completion().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);

        assertEquals(ProbeTask.Status.SUCCEEDED, task.status());
        assertEquals(2, task.results().size());
        assertEquals(ProbeStatus.CONNECTED, task.results().get(0).status());
        assertEquals(ProbeStatus.REFUSED, task.results().get(1).status());
        assertEquals(1, task.summary().get("connected"));
        assertEquals(1, task.summary().get("refused"));
        assertTrue(service.find(task.id()).isPresent());
    }

    @Test
    void rejectsEmptyBatch() {
        assertThrows(ApiException.class, () -> service.submit(List.of(), 400, ProbeTask.Mode.ASYNC));
    }

    @Test
    void rejectsOversizedBatch() {
        List<ProbeTarget> tooMany = java.util.Collections.nCopies(51, ProbeTarget.parse("127.0.0.1:1"));
        ApiException e = assertThrows(ApiException.class,
                () -> service.submit(tooMany, 400, ProbeTask.Mode.ASYNC));
        assertEquals(400, e.status());
        assertEquals("TOO_MANY_TARGETS", e.code());
    }

    @Test
    void reportsFailureInsteadOfLeakingExceptions() throws Exception {
        ProbeTask task = service.submit(List.of(ProbeTarget.parse("127.0.0.1:" + closedPort)),
                400, ProbeTask.Mode.ASYNC);
        task.completion().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals(ProbeTask.Status.SUCCEEDED, task.status());
        assertEquals(ProbeStatus.REFUSED, task.results().get(0).status());
    }
}
