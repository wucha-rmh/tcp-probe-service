package dev.tcpprobe.probe;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpProberTest {

    private final TcpProber prober = new TcpProber(300);

    @AfterEach
    void tearDown() {
        prober.close();
    }

    @Test
    void reportsConnectedForListeningSocket() throws IOException {
        try (ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            startAcceptor(listener);
            ProbeResult result = prober.probe(ProbeTarget.parse("127.0.0.1:" + listener.getLocalPort()));
            assertEquals(ProbeStatus.CONNECTED, result.status(), result.detail());
            assertTrue(result.latencyMs() != null && result.latencyMs() >= 0);
            assertNull(result.dnsMs(), "IP literals skip DNS");
        }
    }

    @Test
    void reportsRefusedWhenNothingListens() throws IOException {
        int port;
        try (ServerSocket listener = new ServerSocket(0)) {
            port = listener.getLocalPort();
        }
        ProbeResult result = prober.probe(ProbeTarget.parse("127.0.0.1:" + port));
        assertEquals(ProbeStatus.REFUSED, result.status(), result.detail());
    }

    @Test
    void reportsUnresolvedForUnknownHost() {
        // 为了确定性：真实 resolver 可能很慢，或者各机器对 NXDOMAIN 的缓存行为不同。
        try (DnsResolver failing = new DnsResolver(1, host -> {
            throw new UnknownHostException(host);
        })) {
            TcpProber isolated = new TcpProber(300, failing, 1000);
            ProbeResult result = isolated.probe(ProbeTarget.parse("definitely-not-a-host.invalid:80"));
            assertEquals(ProbeStatus.UNRESOLVED, result.status(), result.detail());
            assertTrue(result.detail().contains("unknown host"), result.detail());
        }
    }

    @Test
    void mapsStalledResolverToTimeout() {
        try (DnsResolver stalled = new DnsResolver(1, host -> {
            LockSupport.parkNanos(Duration.ofSeconds(3).toNanos());
            return new InetAddress[]{InetAddress.getLoopbackAddress()};
        })) {
            TcpProber isolated = new TcpProber(200, stalled, 50);
            ProbeResult result = isolated.probe(ProbeTarget.parse("slow-resolver.example:80"));
            assertEquals(ProbeStatus.TIMEOUT, result.status(), result.detail());
            assertTrue(result.detail().contains("dns resolution timed out"), result.detail());
        }
    }

    @Test
    void honoursTimeoutWithoutThrowing() {
        // 192.0.2.0/24 是 TEST-NET-1：保证不是真实主机，所以结果要么是黑洞，要么是路由拒绝 ——
        // 两者都是合法结果，绝不能以异常的形式抛出来。
        ProbeResult result = prober.probe(ProbeTarget.parse("192.0.2.1:9"), 200);
        assertTrue(result.status() == ProbeStatus.TIMEOUT || result.status() == ProbeStatus.UNREACHABLE,
                "expected TIMEOUT or UNREACHABLE but got " + result.status() + " (" + result.detail() + ")");
    }

    @Test
    void timesDnsLookupsForHostNames() throws IOException {
        try (ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            startAcceptor(listener);
            ProbeResult result = prober.probe(ProbeTarget.parse("localhost:" + listener.getLocalPort()));
            assertEquals(ProbeStatus.CONNECTED, result.status(), result.detail());
            assertTrue(result.dnsMs() != null, "host names carry a DNS timing");
        }
    }

    private static void startAcceptor(ServerSocket listener) {
        Thread acceptor = new Thread(() -> {
            try (Socket accepted = listener.accept()) {
                // 立即关闭就足够：探测只需要握手完成
                accepted.getRemoteSocketAddress();
            } catch (IOException e) {
                // listener 已被测试关闭
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }
}
