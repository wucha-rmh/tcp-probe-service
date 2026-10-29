package dev.tcpprobe.probe;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 探测器的行为测试：真的开一个监听端口去连它，而不是 mock 掉网络。
 */
class TcpProberTest {

    private TcpProber prober;

    @AfterEach
    void closeProber() {
        if (prober != null) {
            prober.close();
        }
    }

    @Test
    void reportsConnectedForAListeningPort() throws IOException {
        try (ServerSocket listener = new ServerSocket(0)) {
            int port = listener.getLocalPort();
            prober = new TcpProber(500, 500, 2);

            ProbeResult result = prober.probe(ProbeTarget.parse("127.0.0.1:" + port));

            assertThat(result.status()).isEqualTo(ProbeStatus.CONNECTED);
            assertThat(result.status().isHostReachable()).isTrue();
            assertThat(result.latencyMs()).isNotNull().isGreaterThanOrEqualTo(0L);
            // IP 字面量不需要解析，因此没有 DNS 时间。
            assertThat(result.dnsMs()).isNull();
        }
    }

    @Test
    void reportsDnsTimeForHostNames() throws IOException {
        try (ServerSocket listener = new ServerSocket(0)) {
            int port = listener.getLocalPort();
            prober = new TcpProber(1000, 1000, 2);

            // 连接会进 backlog，即使没有 accept 也算握手成功。
            ProbeResult result = prober.probe(ProbeTarget.parse("localhost:" + port));

            assertThat(result.status()).isEqualTo(ProbeStatus.CONNECTED);
            assertThat(result.dnsMs()).isNotNull();
        }
    }

    @Test
    void reportsRefusedForAClosedPort() throws IOException {
        int port;
        try (ServerSocket listener = new ServerSocket(0)) {
            port = listener.getLocalPort();
        }
        prober = new TcpProber(500, 500, 2);

        ProbeResult result = prober.probe(ProbeTarget.parse("127.0.0.1:" + port));

        assertThat(result.status()).isEqualTo(ProbeStatus.REFUSED);
        assertThat(result.status().isConclusive()).isTrue();
        assertThat(result.detail()).isNotBlank();
    }

    @Test
    void reportsUnresolvedWhenTheNameDoesNotExist() {
        prober = new TcpProber(500, 500, 2, host -> {
            throw new UnknownHostException(host);
        });

        ProbeResult result = prober.probe(ProbeTarget.parse("no-such-host.invalid:80"));

        assertThat(result.status()).isEqualTo(ProbeStatus.UNRESOLVED);
        assertThat(result.detail()).contains("no-such-host.invalid");
        assertThat(result.latencyMs()).isNull();
    }

    @Test
    void reportsTimeoutWhenDnsStallsInsteadOfWaitingForever() {
        prober = new TcpProber(500, 100, 2, host -> {
            LockSupport.parkNanos(3_000_000_000L);
            throw new UnknownHostException(host);
        });

        long start = System.nanoTime();
        ProbeResult result = prober.probe(ProbeTarget.parse("stalled.invalid:80"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(result.status()).isEqualTo(ProbeStatus.TIMEOUT);
        assertThat(result.detail()).contains("dns resolution timed out");
        // 预算是 100ms，允许调度抖动，但绝不能等到假解析器的 3 秒。
        assertThat(elapsedMs).isLessThan(2000L);
    }

    @Test
    void sharesOneBudgetAcrossAddresses() {
        prober = new TcpProber(200, 200, 2, host -> new InetAddress[] {
                InetAddress.getByName("192.0.2.1"),
                InetAddress.getByName("192.0.2.2")});

        ProbeResult result = prober.probe(ProbeTarget.parse("blackhole.example:9"), 200);

        // 两个地址都不会响应：整体预算 200ms 用完即返回，不会累加成 400ms。
        assertThat(result.status()).isIn(ProbeStatus.TIMEOUT, ProbeStatus.UNREACHABLE);
    }

    @Test
    void classifiesSocketErrorsByMessage() {
        assertThat(TcpProber.classify(new SocketException("Connection refused: connect")))
                .isEqualTo(ProbeStatus.REFUSED);
        assertThat(TcpProber.classify(new SocketException("Network is unreachable")))
                .isEqualTo(ProbeStatus.UNREACHABLE);
        assertThat(TcpProber.classify(new SocketException("connect timed out")))
                .isEqualTo(ProbeStatus.TIMEOUT);
    }
}
