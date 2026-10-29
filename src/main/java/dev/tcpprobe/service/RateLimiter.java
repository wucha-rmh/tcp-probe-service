package dev.tcpprobe.service;

import dev.tcpprobe.ProbeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
     * @param allowed           本次是否放行
     * @param limit             拒掉它的桶：{@code "global"} 或 {@code "client"}；放行时为 {@code null}
     * @param retryAfterSeconds 建议的重试间隔，仅在拒绝时有意义
     * @param remaining         放行后该客户端桶里剩余的令牌数
     */
    public record Decision(boolean allowed, String limit, long retryAfterSeconds, int remaining) {
    }

    /** 默认最多每隔这么久清理一次空闲客户端桶，避免每个请求都扫一遍 map。 */
    private static final long DEFAULT_SWEEP_INTERVAL_NANOS = 30_000_000_000L;
    /** 默认的「空闲」判定：10 分钟没有请求的客户端桶可以丢掉。 */
    private static final long DEFAULT_IDLE_CLIENT_SECONDS = 600L;

    private final Bucket global;
    private final double clientRps;
    private final int clientBurst;
    private final ConcurrentHashMap<String, Bucket> clients = new ConcurrentHashMap<>();
    private final long sweepIntervalNanos;
    private final long idleSeconds;
    private final AtomicLong lastSweepNanos = new AtomicLong(System.nanoTime());
    private final AtomicLong denied = new AtomicLong();
    private final AtomicLong deniedByGlobal = new AtomicLong();
    private final AtomicLong deniedByClient = new AtomicLong();

    /** 交给 Spring 用这个构造器注入；测试用下面的包内构造器调整清理策略。 */
    @Autowired
    public RateLimiter(ProbeProperties properties) {
        this(properties, DEFAULT_SWEEP_INTERVAL_NANOS, DEFAULT_IDLE_CLIENT_SECONDS);
    }

    /**
     * @param sweepIntervalNanos 两次清理之间至少间隔多久；传 {@code 0} 表示每次申请都尝试清理（供测试用）
     * @param idleSeconds        超过这么久没有请求的客户端桶会被丢掉
     */
    RateLimiter(ProbeProperties properties, long sweepIntervalNanos, long idleSeconds) {
        ProbeProperties.RateLimit limit = properties.rateLimit();
        this.global = new Bucket(limit.globalBurst(), limit.globalRps());
        this.clientRps = limit.clientRps();
        this.clientBurst = limit.clientBurst();
        this.sweepIntervalNanos = sweepIntervalNanos;
        this.idleSeconds = idleSeconds;
    }

    /** 为 {@code clientKey} 申请一次准入；顺带（限频地）清理空闲客户端桶。 */
    public Decision acquire(String clientKey) {
        sweepIdleClients();
        String key = clientKey == null || clientKey.isBlank() ? "-" : clientKey;
        if (!global.tryAcquire()) {
            denied.incrementAndGet();
            deniedByGlobal.incrementAndGet();
            return new Decision(false, "global", global.secondsUntilNextToken(), 0);
        }
        Bucket bucket = clients.computeIfAbsent(key, ignored -> new Bucket(clientBurst, clientRps));
        if (!bucket.tryAcquire()) {
            denied.incrementAndGet();
            deniedByClient.incrementAndGet();
            return new Decision(false, "client", bucket.secondsUntilNextToken(), 0);
        }
        return new Decision(true, null, 0, (int) Math.floor(bucket.available()));
    }

    /** 累计被拒（429）的次数。 */
    public long deniedRequests() {
        return denied.get();
    }

    /** 其中被全局桶拒掉的次数。 */
    public long deniedByGlobal() {
        return deniedByGlobal.get();
    }

    /** 其中被客户端桶拒掉的次数。 */
    public long deniedByClient() {
        return deniedByClient.get();
    }

    /**
     * 惰性清理：不引入后台线程，只在有请求进来时顺手扫一遍，并且最多每 {@code sweepIntervalNanos} 扫一次。
     *
     * <p>用 CAS 抢占「本次清理权」，并发的请求里只有一个真正去扫 map，其余立刻返回。</p>
     */
    private void sweepIdleClients() {
        long now = System.nanoTime();
        long last = lastSweepNanos.get();
        if (now - last < sweepIntervalNanos) {
            return;
        }
        if (lastSweepNanos.compareAndSet(last, now)) {
            forgetIdle(idleSeconds);
        }
    }

    /** 当前跟踪的客户端桶数量，用于指标与测试。 */
    public int trackedClients() {
        return clients.size();
    }

    /** 丢掉空闲超过 {@code idleSeconds} 的客户端桶，避免唯一源地址把 map 撑爆；通常由 {@link #acquire} 惰性触发。 */
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
