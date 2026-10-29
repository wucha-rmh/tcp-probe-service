package dev.tcpprobe.service;

import java.util.function.LongSupplier;

/**
 * 限流器使用的令牌桶。
 *
 * <p>令牌以 {@code refillPerSecond} 的速率持续回填，上限为 {@code capacity}。因此一次突发最
 * 多可以用掉整个桶的大小，而长期速率保持在 {@code refillPerSecond}。时钟可以注入，所以测试
 * 不需要 sleep。</p>
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

    /** 尝试取走 {@code permits} 个令牌。桶太空时返回 false。 */
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

    /** 距离 {@code permits} 个令牌变为可用所需的纳秒数（立即可用时为 0）。 */
    public synchronized long nanosUntilAvailable(double permits) {
        if (permits <= 0) {
            return 0L;
        }
        if (permits > capacity) {
            // 单个请求永远无法满足；报告一个完整的回填周期。
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
