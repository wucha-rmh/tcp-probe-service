package dev.tcpprobe.probe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProbeTargetTest {

    @Test
    void parsesHostAndPort() {
        ProbeTarget target = ProbeTarget.parse("example.com:443");
        assertEquals("example.com", target.host());
        assertEquals(443, target.port());
        assertEquals("example.com:443", target.display());
    }

    @Test
    void parsesBracketedIpv6() {
        ProbeTarget target = ProbeTarget.parse("[::1]:8080");
        assertEquals("::1", target.host());
        assertEquals(8080, target.port());
        assertEquals("[::1]:8080", target.display());
    }

    @Test
    void acceptsIpLiteralsAndTrailingSpace() {
        ProbeTarget v4 = ProbeTarget.parse(" 10.1.2.3:22 ");
        assertEquals("10.1.2.3", v4.host());
        assertEquals(22, v4.port());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "example.com", "example.com:", ":80", "host:0", "host:65536", "host:-1",
            "http://example.com:80", "example.com:80/path", "exa mple.com:80", "[::1:80"})
    void rejectsMalformedTargets(String raw) {
        assertThrows(IllegalArgumentException.class, () -> ProbeTarget.parse(raw));
    }

    @Test
    void rejectsOverlongHost() {
        String host = "a".repeat(300);
        assertThrows(IllegalArgumentException.class, () -> ProbeTarget.parse(host + ":80"));
    }

    @Test
    void lastColonSplitsPort() {
        // Bare IPv6 without brackets is ambiguous on purpose: the port must be bracketed.
        assertThrows(IllegalArgumentException.class, () -> ProbeTarget.parse("::1:80"));
    }
}
