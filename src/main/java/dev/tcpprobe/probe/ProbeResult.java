package dev.tcpprobe.probe;

/**
 * 一次探测尝试的结果。
 *
 * @param target    被探测的端点
 * @param status    分类后的结果
 * @param latencyMs TCP 握手的墙钟毫秒数；握手从未发生时（DNS 失败、预算耗尽）为 {@code null}
 * @param dnsMs     花在域名解析上的墙钟毫秒数；目标是 IP 字面量时为 {@code null}
 * @param detail    简短说明，无需说明时为 {@code null}
 */
public record ProbeResult(ProbeTarget target, ProbeStatus status, Long latencyMs, Long dnsMs, String detail) {

    public static ProbeResult failed(ProbeTarget target, ProbeStatus status, String detail) {
        return new ProbeResult(target, status, null, null, detail);
    }

    public boolean connected() {
        return status == ProbeStatus.CONNECTED;
    }
}
