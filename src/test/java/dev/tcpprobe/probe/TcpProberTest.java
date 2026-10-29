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
        // Deterministic: a real resolver may be slow or NXDOMAIN-cached differently per machine.
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
        // 192.0.2.0/24 is TEST-NET-1: guaranteed not to be a real host, so this is a black hole or a
        // routing refusal — both are legitimate outcomes that must not escape as an exception.
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
                // closing immediately is enough: the probe only needs the handshake
                accepted.getRemoteSocketAddress();
            } catch (IOException e) {
                // listener closed by the test
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }
}
