package dev.tcpprobe.probe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProbeTargetTest {

    @Test
    void parsesHostAndPort() {
        ProbeTarget target = ProbeTarget.parse("example.com:443");

        assertThat(target.host()).isEqualTo("example.com");
        assertThat(target.port()).isEqualTo(443);
        assertThat(target.display()).isEqualTo("example.com:443");
        assertThat(target.isIpLiteral()).isFalse();
    }

    @Test
    void parsesBracketedIpv6() {
        ProbeTarget target = ProbeTarget.parse("[::1]:8080");

        assertThat(target.host()).isEqualTo("::1");
        assertThat(target.port()).isEqualTo(8080);
        assertThat(target.display()).isEqualTo("[::1]:8080");
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(ProbeTarget.parse("  127.0.0.1:22  ").host()).isEqualTo("127.0.0.1");
    }

    @Test
    void recognisesIpLiterals() {
        assertThat(ProbeTarget.parse("192.0.2.7:80").isIpLiteral()).isTrue();
        assertThat(ProbeTarget.parse("example.com:80").isIpLiteral()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "example.com",
            ":80",
            "example.com:",
            "example.com:0",
            "example.com:65536",
            "example.com:notaport",
            "http://example.com:80",
            "example.com:80/path",
            "::1:80",
            "exa mple.com:80"
    })
    void rejectsMalformedTargets(String raw) {
        assertThatThrownBy(() -> ProbeTarget.parse(raw))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOverlyLongHost() {
        String host = "a".repeat(254) + ":80";

        assertThatThrownBy(() -> ProbeTarget.parse(host))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("253");
    }

    @Test
    void rejectsIllegalCharacters() {
        assertThatThrownBy(() -> new ProbeTarget("bad_host!", 80))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host contains characters that are not allowed");
    }
}
