package dev.tcpprobe.probe;

/**
 * 一次探测尝试的结果。
 *
 * @param target    被探测的端点
 * @param status    分类后的结果
 * @param latencyMs TCP 握手的墙钟毫秒数，当握手从未发生（例如 DNS 失败）时为 {@code null}
 * @param dnsMs     花在域名解析上的墙钟毫秒数，当目标本身就是 IP 字面量时为 {@code null}
 * @param detail    简短的人类可读说明，无需说明时为 {@code null}
 */
public record ProbeResult(ProbeTarget target, ProbeStatus status, Long latencyMs, Long dnsMs, String detail) {

    public static ProbeResult of(ProbeTarget target, ProbeStatus status, Long latencyMs, Long dnsMs, String detail) {
        return new ProbeResult(target, status, latencyMs, dnsMs, detail);
    }

    public boolean connected() {
        return status == ProbeStatus.CONNECTED;
    }
}
