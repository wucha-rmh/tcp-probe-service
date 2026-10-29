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
 * Name resolution with a hard time limit.
 *
 * <p>{@link InetAddress#getAllByName} has no timeout of its own: against a dead resolver it can
 * block for tens of seconds, which would silently consume the whole batch deadline and turn every
 * target in that batch into a {@code TIMEOUT} even though only one lookup was slow. Resolving on a
 * bounded pool and waiting with {@code Future.get(timeout)} keeps a single lookup from eating the
 * batch: the caller gets a definite answer, and the stalled lookup thread is abandoned to the OS.</p>
 *
 * <p>The pool also acts as backpressure: at most {@code maxConcurrentLookups} lookups run at once,
 * so a flood of probes cannot spawn an unbounded number of resolver threads. Threads are daemons,
 * and {@link #close()} shuts the pool down with the service.</p>
 */
public final class DnsResolver implements AutoCloseable {

    /** Pluggable lookup, so the timeout path can be tested without a real stalled resolver. */
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
     * Resolves {@code host}, giving up after {@code timeoutMs}.
     *
     * @throws UnknownHostException when the name does not resolve
     * @throws DnsTimeoutException  when the resolver did not answer within the budget
     */
    public InetAddress[] resolve(String host, int timeoutMs) throws UnknownHostException, DnsTimeoutException {
        Future<InetAddress[]> future = pool.submit(() -> lookup.resolve(host));
        try {
            return future.get(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // The JDK resolver ignores interrupts, so this only frees the queue slot; the thread
            // itself returns when the OS gives up. Documented tradeoff: bounded thread count, no
            // blocking of the probe worker.
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
