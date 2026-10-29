package dev.tcpprobe.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 两级令牌桶限流器：一个全局桶保护整个服务，另一个按客户端分桶（key 通常是调用方的 IP）。
 *
 * <p>为什么用令牌桶而不是固定窗口？它既能容忍突发（调用方可以一次性用掉自己的全部突发额度），
 * 同时施加严格的长期速率，而且不需要按窗口重置状态，也不需要后台定时器。代价是每个请求多
 * 一点浮点运算。</p>
 *
 * <p>空闲的客户端桶会被周期性清理，这样源源不断的唯一源地址不会让 map 无限增长。</p>
 */
public final class RateLimiter {

    /** 单次准入尝试的结果。 */
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
     * 尝试为 {@code clientKey} 准入一个请求。
     *
     * <p>先消耗全局桶；如果全局桶拒绝，客户端桶就保持不动，这样一个吵闹的邻居不会把其他人的
     * 额度一起抽干。</p>
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

    /** 当前跟踪的客户端桶数量（暴露给指标和测试使用）。 */
    public int trackedClients() {
        return clients.size();
    }

    /** 丢弃空闲超过 {@code idleSeconds} 的客户端桶。 */
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
