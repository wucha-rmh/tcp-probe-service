package dev.tcpprobe.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Two-level token-bucket rate limiter: one global bucket protecting the service as a whole and one
 * bucket per client key (usually the caller's IP).
 *
 * <p>Why a token bucket instead of a fixed window? It smooths bursts (a caller may use its whole
 * burst budget at once) while enforcing a hard long-run rate, and it needs no per-window state
 * reset or background timer. The cost is a little float arithmetic per request.</p>
 *
 * <p>Idle client buckets are swept periodically so that a stream of unique source addresses cannot
 * grow the map without bound.</p>
 */
public final class RateLimiter {

    /** Result of a single admission attempt. */
    public record Decision(boolean allowed, long retryAfterSeconds, double remaining, String limitReason) {

        public static Decision allow(double remaining) {
            return new Decision(true, 0, remaining, null);
        }

        public static Decision deny(long retryAfterSeconds, String reason) {
            return new Decision(false, Math.max(1, retryAfterSeconds), 0, reason);
        }
    }

    private static final class ClientState {
        final TokenBucket bucket;
        final AtomicLong lastSeenNanos;

        ClientState(TokenBucket bucket, long now) {
            this.bucket = bucket;
            this.lastSeenNanos = new AtomicLong(now);
        }
    }

    private final TokenBucket global;
    private final double clientCapacity;
    private final double clientRefillPerSecond;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, ClientState> clients = new ConcurrentHashMap<>();

    public RateLimiter(double globalRps, int globalBurst, double clientRps, int clientBurst) {
        this(globalRps, globalBurst, clientRps, clientBurst, System::nanoTime);
    }

    RateLimiter(double globalRps, int globalBurst, double clientRps, int clientBurst, LongSupplier nanoClock) {
        this.global = new TokenBucket(globalBurst, globalRps, nanoClock);
        this.clientCapacity = clientBurst;
        this.clientRefillPerSecond = clientRps;
        this.nanoClock = nanoClock;
    }

    /**
     * Attempts to admit one request for {@code clientKey}.
     *
     * <p>The global bucket is consumed first; if it refuses, the client bucket is left untouched so
     * that one noisy neighbour does not drain everybody else's allowance.</p>
     */
    public Decision acquire(String clientKey) {
        if (!global.tryAcquire(1)) {
            return Decision.deny(retryAfter(global), "global");
        }
        String key = clientKey == null || clientKey.isBlank() ? "unknown" : clientKey;
        long now = nanoClock.getAsLong();
        ClientState state = clients.computeIfAbsent(key,
                k -> new ClientState(new TokenBucket(clientCapacity, clientRefillPerSecond, nanoClock), now));
        state.lastSeenNanos.set(now);
        if (!state.bucket.tryAcquire(1)) {
            return Decision.deny(retryAfter(state.bucket), "client");
        }
        return Decision.allow(state.bucket.available());
    }

    /** Number of client buckets currently tracked (exposed for metrics and tests). */
    public int trackedClients() {
        return clients.size();
    }

    /** Drops client buckets that have been idle for longer than {@code idleSeconds}. */
    public int sweepIdle(long idleSeconds) {
        long now = nanoClock.getAsLong();
        long threshold = idleSeconds * 1_000_000_000L;
        int before = clients.size();
        clients.entrySet().removeIf(e -> now - e.getValue().lastSeenNanos.get() > threshold);
        return before - clients.size();
    }

    private static long retryAfter(TokenBucket bucket) {
        long nanos = bucket.nanosUntilAvailable(1);
        return Math.max(1L, (nanos + 999_999_999L) / 1_000_000_000L);
    }
}
