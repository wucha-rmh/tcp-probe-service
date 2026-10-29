package dev.tcpprobe.probe;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.SocketChannel;
import java.util.Locale;

/**
 * 手写的 TCP 连通性探测器。
 *
 * <p>不使用任何第三方探测库：实现打开一个普通的 {@link SocketChannel}，在限定超时内完成 TCP 握手，
 * 并对结果进行分类。连接随后立即关闭——目标是可达性，而不是载荷交换。</p>
 *
 * <p>设计说明：</p>
 * <ul>
 *   <li><b>DNS 的测量与限时都独立进行。</b>{@link InetAddress#getAllByName} 的阻塞时间可能远超连接超时，
 *       而且不受该超时约束。因此一次查找会通过 {@link DnsResolver} 在自带预算下执行，并作为 {@code dnsMs}
 *       上报，从而让“解析器慢”与“网络慢”保持可区分。</li>
 *   <li><b>每个解析出的地址都会被尝试。</b>一个拥有多条 A/AAAA 记录的主机，即使其中一条被过滤，
 *       只要另一个地址有响应，仍会报告 {@code CONNECTED}。每个地址的连接超时等于请求的超时，
 *       因此最坏情况是 {@code addresses × timeout}；批量级截止时间会将其截断。</li>
 *   <li><b>不重试。</b>在探测器内部重试会模糊延迟测量；需要重试的调用方可以重新提交任务，
 *       这样能让 API 保持诚实。</li>
 * </ul>
 */
public final class TcpProber {

    /** 放弃前最多尝试多少个已解析出的地址。 */
    private static final int MAX_ADDRESS_ATTEMPTS = 4;

    /**
     * @param connectTimeoutMs 默认握手超时，在调用方未覆盖时使用
     */
    public TcpProber(int connectTimeoutMs) {
        this(connectTimeoutMs, new DnsResolver(8), 2000);
    }

    /**
     * @param connectTimeoutMs 默认握手超时
     * @param resolver         由探测器持有；{@link #close()} 会将其关闭
     * @param dnsTimeoutMs     一次域名查找的预算
     */
    public TcpProber(int connectTimeoutMs, DnsResolver resolver, int dnsTimeoutMs) {
        if (connectTimeoutMs < 1) {
            throw new IllegalArgumentException("connectTimeoutMs must be positive");
        }
        if (dnsTimeoutMs < 1) {
            throw new IllegalArgumentException("dnsTimeoutMs must be positive");
        }
        this.defaultConnectTimeoutMs = connectTimeoutMs;
        this.resolver = java.util.Objects.requireNonNull(resolver, "resolver");
        this.dnsTimeoutMs = dnsTimeoutMs;
    }

    private final int defaultConnectTimeoutMs;
    private final DnsResolver resolver;
    private final int dnsTimeoutMs;

    public int defaultConnectTimeoutMs() {
        return defaultConnectTimeoutMs;
    }

    public int dnsTimeoutMs() {
        return dnsTimeoutMs;
    }

    /** 释放解析器线程池；此后不得再使用该探测器。 */
    public void close() {
        resolver.close();
    }

    public ProbeResult probe(ProbeTarget target) {
        return probe(target, defaultConnectTimeoutMs);
    }

    /**
     * 探测 {@code target} 且从不抛异常：每一种失败模式都会被映射到一个 {@link ProbeStatus}，
     * 因此一批目标总能得到每个目标各自的结果。
     */
    public ProbeResult probe(ProbeTarget target, int connectTimeoutMs) {
        if (connectTimeoutMs < 1) {
            connectTimeoutMs = defaultConnectTimeoutMs;
        }

        long dnsStart = System.nanoTime();
        InetAddress[] addresses;
        boolean literal = isLiteral(target.host());
        try {
            addresses = resolver.resolve(target.host(), dnsTimeoutMs);
        } catch (UnknownHostException e) {
            return ProbeResult.of(target, ProbeStatus.UNRESOLVED, null, null,
                    "unknown host: " + target.host());
        } catch (DnsTimeoutException e) {
            return ProbeResult.of(target, ProbeStatus.TIMEOUT, null, null, e.getMessage());
        } catch (RuntimeException e) {
            return ProbeResult.of(target, ProbeStatus.ERROR, null, null, safeMessage(e));
        }
        long dnsMs = Math.max(0L, (System.nanoTime() - dnsStart) / 1_000_000L);

        ProbeStatus lastStatus = null;
        String lastDetail = null;
        for (int i = 0; i < Math.min(addresses.length, MAX_ADDRESS_ATTEMPTS); i++) {
            InetAddress address = addresses[i];
            long start = System.nanoTime();
            try (SocketChannel channel = SocketChannel.open()) {
                Socket socket = channel.socket();
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(address, target.port()), connectTimeoutMs);
                long latencyMs = Math.max(0L, (System.nanoTime() - start) / 1_000_000L);
                return ProbeResult.of(target, ProbeStatus.CONNECTED, latencyMs, literal ? null : dnsMs, null);
            } catch (SocketTimeoutException e) {
                lastStatus = ProbeStatus.TIMEOUT;
                lastDetail = "connect timed out after " + connectTimeoutMs + " ms";
            } catch (NoRouteToHostException e) {
                lastStatus = ProbeStatus.UNREACHABLE;
                lastDetail = "no route to host: " + address.getHostAddress();
            } catch (PortUnreachableException e) {
                lastStatus = ProbeStatus.UNREACHABLE;
                lastDetail = "port unreachable: " + address.getHostAddress();
            } catch (SocketException e) {
                lastStatus = classifySocketException(e);
                lastDetail = safeMessage(e);
                if (lastStatus == ProbeStatus.REFUSED) {
                    // 连接被拒绝已经证明主机可达；没必要把剩余超时浪费在同一个名字的其他地址上。
                    break;
                }
            } catch (IOException e) {
                lastStatus = ProbeStatus.ERROR;
                lastDetail = e.getClass().getSimpleName() + ": " + safeMessage(e);
            } catch (RuntimeException e) {
                lastStatus = ProbeStatus.ERROR;
                lastDetail = e.getClass().getSimpleName() + ": " + safeMessage(e);
            }
        }
        // 至少已尝试过一个地址（getAllByName 从不返回空数组）。
        return ProbeResult.of(target, lastStatus == null ? ProbeStatus.ERROR : lastStatus,
                null, literal ? null : dnsMs, lastDetail);
    }

    /** 把套接字层面的失败映射为 API 可见的状态。对测试可见。 */
    static ProbeStatus classifySocketException(SocketException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (e instanceof ConnectException
                && (msg.contains("refused") || msg.contains("拒绝") || msg.isEmpty())) {
            return ProbeStatus.REFUSED;
        }
        if (msg.contains("refused") || msg.contains("拒绝")) {
            return ProbeStatus.REFUSED;
        }
        if (msg.contains("unreachable") || msg.contains("no route")) {
            return ProbeStatus.UNREACHABLE;
        }
        if (msg.contains("timed out") || msg.contains("timeout")) {
            return ProbeStatus.TIMEOUT;
        }
        return ProbeStatus.UNREACHABLE;
    }

    private static boolean isLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        return host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$");
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
}
