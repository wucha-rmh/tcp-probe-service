package dev.tcpprobe.probe;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 带硬性时间限制的域名解析。
 *
 * <p>{@link InetAddress#getAllByName} 自身没有超时：面对一个失效的解析器，它可能阻塞数十秒，
 * 从而悄悄耗尽整个批量的截止时间，把该批次中的每个目标都变成 {@code TIMEOUT}，哪怕只有一个查找变慢。
 * 在有界线程池上解析、并用 {@code Future.get(timeout)} 等待，可以避免单个查找拖垮整个批量：
 * 调用方总能得到确定的答复，而卡住的查找线程则被丢弃给操作系统。</p>
 *
 * <p>线程池同时充当背压：最多只有 {@code maxConcurrentLookups} 个查找同时运行，
 * 因此大量探测不会催生数量不受限的解析线程。线程均为守护线程，
 * 且 {@link #close()} 会随服务一起关闭线程池。</p>
 */
public final class DnsResolver implements AutoCloseable {

    /** 可插拔的查找接口，使得无需真实卡死的解析器也能测试超时路径。 */
    @FunctionalInterface
    public interface Lookup {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final ExecutorService pool;
    private final Lookup lookup;

    public DnsResolver(int maxConcurrentLookups) {
        this(maxConcurrentLookups, InetAddress::getAllByName);
    }

    DnsResolver(int maxConcurrentLookups, Lookup lookup) {
        if (maxConcurrentLookups < 1) {
            throw new IllegalArgumentException("maxConcurrentLookups must be positive");
        }
        AtomicInteger threadIds = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(maxConcurrentLookups, runnable -> {
            Thread thread = new Thread(runnable, "dns-" + threadIds.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.lookup = lookup;
    }

    /**
     * 解析 {@code host}，在超过 {@code timeoutMs} 后放弃。
     *
     * @throws UnknownHostException 当域名无法解析时
     * @throws DnsTimeoutException  当解析器未在预算内应答时
     */
    public InetAddress[] resolve(String host, int timeoutMs) throws UnknownHostException, DnsTimeoutException {
        Future<InetAddress[]> future = pool.submit(() -> lookup.resolve(host));
        try {
            return future.get(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // JDK 解析器忽略中断，因此这里只是释放队列槽位；线程本身要等操作系统放弃后才返回。
            // 已记录的取舍：线程数有界，且不阻塞探测工作线程。
            future.cancel(true);
            throw new DnsTimeoutException("dns resolution timed out after " + timeoutMs + " ms");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new DnsTimeoutException("dns resolution interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UnknownHostException unknownHost) {
                throw unknownHost;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("unexpected resolver failure", cause);
        }
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
