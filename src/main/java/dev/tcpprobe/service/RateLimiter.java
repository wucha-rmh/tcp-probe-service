package dev.tcpprobe.service;

import dev.tcpprobe.ProbeProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 两级令牌桶限流：一个全局桶保护整个服务，另一个按客户端（通常是对端 IP）分桶。
 *
 * <p>选令牌桶而不是固定窗口，是因为它同时满足两件事：允许突发（调用方可以在一个瞬间用掉
 * 自己的全部额度），又施加严格的长期速率；不需要按窗口重置状态，也不需要后台定时器。
 * 代价只是每个请求几次浮点运算。</p>
 *
 * <p>先扣全局桶、再扣客户端桶：全局已经拒绝时不碰客户端桶，这样一个吵闹的邻居不会顺带把
 * 别人的额度抽干。</p>
 */
@Component
public final class RateLimiter {

    /**
     * @param allowed          本次是否放行
     * @param retryAfterSeconds 建议的重试间隔，仅在拒绝时有意义
     * @param remaining        放行后该客户端桶里剩余的令牌数
     */
    public record Decision(boolean allowed, long retryAfterSeconds, int remaining) {
    }

    private final Bucket global;
    private final double clientRps;
    private final int clientBurst;
    private final ConcurrentHashMap<String, Bucket> clients = new ConcurrentHashMap<>();

    public RateLimiter(ProbeProperties properties) {
        ProbeProperties.RateLimit limit = properties.rateLimit();
        this.global = new Bucket(limit.globalBurst(), limit.globalRps());
        this.clientRps = limit.clientRps();
        this.clientBurst = limit.clientBurst();
    }

    /** 为 {@code clientKey} 申请一次准入。 */
    public Decision acquire(String clientKey) {
        String key = clientKey == null || clientKey.isBlank() ? "-" : clientKey;
        if (!global.tryAcquire()) {
            return new Decision(false, global.secondsUntilNextToken(), 0);
        }
        Bucket bucket = clients.computeIfAbsent(key, ignored -> new Bucket(clientBurst, clientRps));
        if (!bucket.tryAcquire()) {
            return new Decision(false, bucket.secondsUntilNextToken(), 0);
        }
        return new Decision(true, 0, (int) Math.floor(bucket.available()));
    }

    /** 当前跟踪的客户端桶数量，用于指标与测试。 */
    public int trackedClients() {
        return clients.size();
    }

    /** 丢掉空闲超过 {@code idleSeconds} 的客户端桶，避免唯一源地址把 map 撑爆。 */
    public int forgetIdle(long idleSeconds) {
        long idleNanos = idleSeconds * 1_000_000_000L;
        long now = System.nanoTime();
        int before = clients.size();
        clients.entrySet().removeIf(entry -> now - entry.getValue().lastActivityNanos() > idleNanos);
        return before - clients.size();
    }

    /** 一个令牌桶；所有方法都在同一把锁内，因此不需要原子改动。 */
    private static final class Bucket {

        private final double capacity;
        private final double refillPerSecond;
        private double tokens;
        private long lastRefillNanos;
        private volatile long lastActivityNanos;

        Bucket(int capacity, double refillPerSecond) {
            this.capacity = Math.max(1, capacity);
            this.refillPerSecond = Math.max(0.000_001, refillPerSecond);
            this.tokens = this.capacity;
            this.lastRefillNanos = System.nanoTime();
            this.lastActivityNanos = this.lastRefillNanos;
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            refill(now);
            lastActivityNanos = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized double available() {
            refill(System.nanoTime());
            return tokens;
        }

        synchronized long secondsUntilNextToken() {
            long now = System.nanoTime();
            refill(now);
            if (tokens >= 1.0) {
                return 0L;
            }
            long nanos = (long) ((1.0 - tokens) / refillPerSecond * 1_000_000_000L);
            return Math.max(1L, (nanos + 999_999_999L) / 1_000_000_000L);
        }

        long lastActivityNanos() {
            return lastActivityNanos;
        }

        private void refill(long now) {
            long elapsed = now - lastRefillNanos;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed / 1_000_000_000.0 * refillPerSecond);
                lastRefillNanos = now;
            }
        }
    }
}
