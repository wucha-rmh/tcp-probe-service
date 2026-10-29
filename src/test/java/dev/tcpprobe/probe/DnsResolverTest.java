package dev.tcpprobe.probe;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DnsResolverTest {

    @Test
    void resolvesLoopbackThroughTheRealResolver() throws Exception {
        try (DnsResolver resolver = new DnsResolver(4)) {
            InetAddress[] addresses = resolver.resolve("localhost", 5000);
            assertTrue(addresses.length >= 1, "localhost must resolve");
        }
    }

    @Test
    void propagatesUnknownHost() {
        try (DnsResolver resolver = new DnsResolver(1, host -> {
            throw new UnknownHostException(host);
        })) {
            UnknownHostException e = assertThrows(UnknownHostException.class,
                    () -> resolver.resolve("nope.invalid", 200));
            assertTrue(e.getMessage().contains("nope.invalid"));
        }
    }

    @Test
    void givesUpOnStalledLookupQuickly() {
        try (DnsResolver resolver = new DnsResolver(1, host -> {
            LockSupport.parkNanos(Duration.ofSeconds(5).toNanos());
            return new InetAddress[0];
        })) {
            long start = System.nanoTime();
            DnsTimeoutException e = assertThrows(DnsTimeoutException.class,
                    () -> resolver.resolve("stalled.example", 100));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(e.getMessage().contains("100 ms"), e.getMessage());
            assertTrue(elapsedMs < 2000, "must give up promptly, took " + elapsedMs + " ms");
        }
    }

    @Test
    void rejectsNonPositivePoolSize() {
        assertThrows(IllegalArgumentException.class, () -> new DnsResolver(0));
    }
}
