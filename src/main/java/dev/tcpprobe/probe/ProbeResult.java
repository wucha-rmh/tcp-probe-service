package dev.tcpprobe.probe;

/**
 * Result of one probe attempt.
 *
 * @param target    endpoint that was probed
 * @param status    classified outcome
 * @param latencyMs wall-clock milliseconds of the TCP handshake, {@code null} when the handshake
 *                  never took place (for example DNS failure)
 * @param dnsMs     wall-clock milliseconds spent in name resolution, {@code null} when the target
 *                  was already an IP literal
 * @param detail    short human readable explanation, {@code null} when none is needed
 */
public record ProbeResult(ProbeTarget target, ProbeStatus status, Long latencyMs, Long dnsMs, String detail) {

    public static ProbeResult of(ProbeTarget target, ProbeStatus status, Long latencyMs, Long dnsMs, String detail) {
        return new ProbeResult(target, status, latencyMs, dnsMs, detail);
    }

    public boolean connected() {
        return status == ProbeStatus.CONNECTED;
    }
}
