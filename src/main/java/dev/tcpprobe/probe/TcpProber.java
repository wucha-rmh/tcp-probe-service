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
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 手写的 TCP 连通性探测器。
 *
 * <p>全部逻辑就是打开一个 {@link SocketChannel}、在限定时间内完成 TCP 握手，然后按结果分类。
 * 没有第三方探测库：这个服务的价值恰恰在于把这段逻辑写清楚、可解释。</p>
 *
 * <p>四个刻意的设计点：</p>
 * <ol>
 *   <li><b>DNS 单独限时。</b>{@link InetAddress#getAllByName} 自身没有超时参数，遇到坏掉的解析器
 *       可能阻塞几十秒。所以查找放到 {@code dnsPool} 上执行，用 {@code Future.get(budget)} 等，
 *       超时就放弃——否则一个域名会让整批目标一起变成超时。</li>
 *   <li><b>多个 A/AAAA 记录逐个试。</b>某个地址被过滤掉时，另一个地址仍可能通，报告
 *       {@code CONNECTED} 比报告第一个地址的失败更有用。一旦拿到 {@code REFUSED} 就直接结束：
 *       主机已经证明可达，没必要把剩余预算花在同一个名字的其他地址上。</li>
 *   <li><b>一个目标的总预算，而不是每个地址一份预算。</b>DNS 预算加握手预算就是上限，
 *       逐地址共享剩余时间，因此最坏耗时是可预测的（批量层还有整体截止时间兜底）。</li>
 *   <li><b>不重试。</b>探测器内部重试会让延迟数字失去意义；需要重试的调用方重新提交任务即可。</li>
 * </ol>
 */
public final class TcpProber implements AutoCloseable {

    /** 域名解析的可替换实现，只为测试“卡住的解析器”而存在。 */
    @FunctionalInterface
    public interface Lookup {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** 一个域名最多试几个地址，防止多 A 记录把预算摊得太薄。 */
    private static final int MAX_ADDRESS_ATTEMPTS = 4;

    private final int connectTimeoutMs;
    private final int dnsTimeoutMs;
    private final Lookup lookup;
    private final ExecutorService dnsPool;

    public TcpProber(int connectTimeoutMs, int dnsTimeoutMs, int dnsThreads) {
        this(connectTimeoutMs, dnsTimeoutMs, dnsThreads, InetAddress::getAllByName);
    }

    TcpProber(int connectTimeoutMs, int dnsTimeoutMs, int dnsThreads, Lookup lookup) {
        if (connectTimeoutMs < 1 || dnsTimeoutMs < 1 || dnsThreads < 1) {
            throw new IllegalArgumentException("timeouts and dnsThreads must be positive");
        }
        this.connectTimeoutMs = connectTimeoutMs;
        this.dnsTimeoutMs = dnsTimeoutMs;
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "dns-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.dnsPool = Executors.newFixedThreadPool(dnsThreads, factory);
    }

    public int connectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int dnsTimeoutMs() {
        return dnsTimeoutMs;
    }

    /** 探测目标，使用默认连接预算。 */
    public ProbeResult probe(ProbeTarget target) {
        return probe(target, connectTimeoutMs);
    }

    /**
     * 探测目标，总预算为 {@code budgetMs}（DNS + 一个或多个地址的握手）。
     *
     * <p>永远不抛异常：每一种失败都会变成带 {@link ProbeStatus} 的结果，
     * 这样一批目标总能拿到每个目标各自的结果。</p>
     */
    public ProbeResult probe(ProbeTarget target, int budgetMs) {
        int budget = budgetMs < 1 ? connectTimeoutMs : budgetMs;

        long dnsStart = System.nanoTime();
        InetAddress[] addresses;
        try {
            addresses = target.isIpLiteral()
                    ? new InetAddress[] {InetAddress.getByName(target.host())}
                    : resolve(target.host());
        } catch (DnsTimeoutException e) {
            return ProbeResult.failed(target, ProbeStatus.TIMEOUT, e.getMessage());
        } catch (UnknownHostException e) {
            return ProbeResult.failed(target, ProbeStatus.UNRESOLVED, "unknown host: " + target.host());
        } catch (RuntimeException e) {
            return ProbeResult.failed(target, ProbeStatus.ERROR, describe(e));
        }
        Long dnsMs = target.isIpLiteral() ? null : Math.max(0L, (System.nanoTime() - dnsStart) / 1_000_000L);

        long deadline = System.nanoTime() + budget * 1_000_000L;
        ProbeStatus lastStatus = ProbeStatus.TIMEOUT;
        String lastDetail = "budget exhausted before the connection attempt started";

        int attempts = Math.min(addresses.length, MAX_ADDRESS_ATTEMPTS);
        for (int i = 0; i < attempts; i++) {
            InetAddress address = addresses[i];
            long remainingNanos = deadline - System.nanoTime();
            int remainingMs = (int) TimeUnit.NANOSECONDS.toMillis(remainingNanos);
            if (remainingMs <= 0) {
                lastStatus = ProbeStatus.TIMEOUT;
                lastDetail = "budget of " + budget + " ms exhausted before " + address.getHostAddress()
                        + " was tried";
                break;
            }

            long start = System.nanoTime();
            try (SocketChannel channel = SocketChannel.open()) {
                Socket socket = channel.socket();
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(address, target.port()), remainingMs);
                long latencyMs = Math.max(0L, (System.nanoTime() - start) / 1_000_000L);
                return new ProbeResult(target, ProbeStatus.CONNECTED, latencyMs, dnsMs, null);
            } catch (SocketTimeoutException e) {
                lastStatus = ProbeStatus.TIMEOUT;
                lastDetail = "connect timed out after " + remainingMs + " ms";
            } catch (PortUnreachableException e) {
                lastStatus = ProbeStatus.UNREACHABLE;
                lastDetail = "port unreachable: " + address.getHostAddress();
            } catch (NoRouteToHostException e) {
                lastStatus = ProbeStatus.UNREACHABLE;
                lastDetail = "no route to host: " + address.getHostAddress();
            } catch (SocketException e) {
                lastStatus = classify(e);
                lastDetail = describe(e);
                if (lastStatus == ProbeStatus.REFUSED) {
                    break;
                }
            } catch (IOException | RuntimeException e) {
                lastStatus = ProbeStatus.ERROR;
                lastDetail = describe(e);
            }
        }
        return new ProbeResult(target, lastStatus, null, dnsMs, lastDetail);
    }

    /** 在有界线程池上解析，超过 {@code dnsTimeoutMs} 就放弃。 */
    private InetAddress[] resolve(String host) throws UnknownHostException, DnsTimeoutException {
        Future<InetAddress[]> future = dnsPool.submit(() -> lookup.resolve(host));
        try {
            return future.get(dnsTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // JDK 解析器不响应中断，这里只是把队列槽位还回去；线程本身要等系统调用返回。
            future.cancel(true);
            throw new DnsTimeoutException("dns resolution timed out after " + dnsTimeoutMs + " ms");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new DnsTimeoutException("dns resolution was interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UnknownHostException unknown) {
                throw unknown;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    /** 把套接字层面的失败映射为 API 可见的状态。对测试可见。 */
    static ProbeStatus classify(SocketException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("refused") || message.contains("拒绝")) {
            return ProbeStatus.REFUSED;
        }
        if (e instanceof ConnectException && message.isEmpty()) {
            return ProbeStatus.REFUSED;
        }
        if (message.contains("unreachable") || message.contains("no route")) {
            return ProbeStatus.UNREACHABLE;
        }
        if (message.contains("timed out") || message.contains("timeout")) {
            return ProbeStatus.TIMEOUT;
        }
        return ProbeStatus.UNREACHABLE;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    /** 停掉 DNS 线程池；之后不可再用该探测器。 */
    @Override
    public void close() {
        dnsPool.shutdownNow();
    }

    /** 解析超时，映射为 {@link ProbeStatus#TIMEOUT}。 */
    static final class DnsTimeoutException extends Exception {

        private static final long serialVersionUID = 1L;

        DnsTimeoutException(String message) {
            super(message);
        }
    }
}
