package dev.tcpprobe.service;

import java.util.function.LongSupplier;

/**
 * Token bucket used by the rate limiter.
 *
 * <p>Tokens refill continuously at {@code refillPerSecond} up to {@code capacity}. A burst is
 * therefore allowed up to the bucket size while the long-run rate stays at
 * {@code refillPerSecond}. The clock is injectable so tests do not need to sleep.</p>
 */
public final class TokenBucket {

    private final double capacity;
    private final double refillPerSecond;
    private final LongSupplier nanoClock;
    private double tokens;
    private long lastNanos;

    public TokenBucket(double capacity, double refillPerSecond) {
        this(capacity, refillPerSecond, System::nanoTime);
    }

    TokenBucket(double capacity, double refillPerSecond, LongSupplier nanoClock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (refillPerSecond <= 0) {
            throw new IllegalArgumentException("refillPerSecond must be positive");
        }
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.nanoClock = nanoClock;
        this.tokens = capacity;
        this.lastNanos = nanoClock.getAsLong();
    }

    /** Tries to take {@code permits} tokens. Returns false when the bucket is too empty. */
    public synchronized boolean tryAcquire(double permits) {
        if (permits <= 0) {
            return true;
        }
        if (permits > capacity) {
            return false;
        }
        refill();
        if (tokens >= permits) {
            tokens -= permits;
            return true;
        }
        return false;
    }

    /** Nanoseconds until {@code permits} tokens become available (0 when available now). */
    public synchronized long nanosUntilAvailable(double permits) {
        if (permits <= 0) {
            return 0L;
        }
        if (permits > capacity) {
            // Cannot ever be satisfied by a single request; report a full refill cycle.
            return (long) (capacity / refillPerSecond * 1_000_000_000L);
        }
        refill();
        if (tokens >= permits) {
            return 0L;
        }
        double missing = permits - tokens;
        return (long) (missing / refillPerSecond * 1_000_000_000L);
    }

    public synchronized double available() {
        refill();
        return tokens;
    }

    private void refill() {
        long now = nanoClock.getAsLong();
        long elapsed = now - lastNanos;
        if (elapsed <= 0) {
            return;
        }
        lastNanos = now;
        tokens = Math.min(capacity, tokens + elapsed / 1_000_000_000.0 * refillPerSecond);
    }
}
