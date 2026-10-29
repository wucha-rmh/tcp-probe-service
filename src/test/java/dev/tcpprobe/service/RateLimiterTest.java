package dev.tcpprobe.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    void countsDenialsPerBucket() {
        RateLimiter clientLimited = limiter(0.2, 1, 1000, 1000);
        assertThat(clientLimited.acquire("10.0.0.1").allowed()).isTrue();
        assertThat(clientLimited.acquire("10.0.0.1").allowed()).isFalse();
        assertThat(clientLimited.deniedRequests()).isEqualTo(1);
        assertThat(clientLimited.deniedByClient()).isEqualTo(1);
        assertThat(clientLimited.deniedByGlobal()).isZero();

        RateLimiter globalLimited = limiter(1000, 1000, 0.2, 1);
        assertThat(globalLimited.acquire("10.0.0.1").allowed()).isTrue();
        assertThat(globalLimited.acquire("10.0.0.2").allowed()).isFalse();
        assertThat(globalLimited.deniedRequests()).isEqualTo(1);
        assertThat(globalLimited.deniedByGlobal()).isEqualTo(1);
        assertThat(globalLimited.deniedByClient()).isZero();
    }

    private static RateLimiter limiter(double clientRps, int clientBurst, double globalRps, int globalBurst) {
        return new RateLimiter(new dev.tcpprobe.ProbeProperties(
                2000, 2000, 8, 200, 16, 2, 600, 30000, 1048576,
                new dev.tcpprobe.ProbeProperties.RateLimit(globalRps, globalBurst, clientRps, clientBurst)));
    }

    private static dev.tcpprobe.ProbeProperties properties() {
        return new dev.tcpprobe.ProbeProperties(
                2000, 2000, 8, 200, 16, 2, 600, 30000, 1048576,
                new dev.tcpprobe.ProbeProperties.RateLimit(1000, 1000, 1000, 1000));
    }

    @Test
    void allowsTheBurstThenDenies() {
        RateLimiter limiter = limiter(0.2, 2, 1000, 1000);

        RateLimiter.Decision allowed = limiter.acquire("10.0.0.1");
        assertThat(allowed.allowed()).isTrue();
        // 放行时没有「哪个桶拒绝」可言。
        assertThat(allowed.limit()).isNull();
        assertThat(limiter.acquire("10.0.0.1").allowed()).isTrue();

        RateLimiter.Decision denied = limiter.acquire("10.0.0.1");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.limit()).isEqualTo("client");
        assertThat(denied.retryAfterSeconds()).isGreaterThanOrEqualTo(1L);
        assertThat(denied.remaining()).isZero();
    }

    @Test
    void reportsRemainingTokensAfterEachRequest() {
        RateLimiter limiter = limiter(0.2, 5, 1000, 1000);

        assertThat(limiter.acquire("10.0.0.1").remaining()).isEqualTo(4);
        assertThat(limiter.acquire("10.0.0.1").remaining()).isEqualTo(3);
    }

    @Test
    void refillsOverTime() throws InterruptedException {
        RateLimiter limiter = limiter(100, 1, 1000, 1000);

        assertThat(limiter.acquire("10.0.0.1").allowed()).isTrue();
        assertThat(limiter.acquire("10.0.0.1").allowed()).isFalse();

        Thread.sleep(60);
        assertThat(limiter.acquire("10.0.0.1").allowed()).isTrue();
    }

    @Test
    void keepsClientsIndependent() {
        RateLimiter limiter = limiter(0.2, 1, 1000, 1000);

        assertThat(limiter.acquire("10.0.0.1").allowed()).isTrue();
        assertThat(limiter.acquire("10.0.0.1").allowed()).isFalse();
        // 另一个客户端有自己的桶，不受影响。
        assertThat(limiter.acquire("10.0.0.2").allowed()).isTrue();
        assertThat(limiter.trackedClients()).isEqualTo(2);
    }

    @Test
    void globalBucketProtectsTheService() {
        RateLimiter limiter = limiter(1000, 1000, 0.2, 1);

        assertThat(limiter.acquire("10.0.0.1").allowed()).isTrue();
        RateLimiter.Decision denied = limiter.acquire("10.0.0.2");
        assertThat(denied.allowed()).isFalse();
        // 全局桶拒绝时，客户端桶还是满的——这一点必须能从 Decision 里看出来。
        assertThat(denied.limit()).isEqualTo("global");
        assertThat(denied.retryAfterSeconds()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void forgetsIdleClients() throws InterruptedException {
        RateLimiter limiter = limiter(1000, 1000, 1000, 1000);
        limiter.acquire("10.0.0.1");
        limiter.acquire("10.0.0.2");

        Thread.sleep(10);
        assertThat(limiter.forgetIdle(0)).isEqualTo(2);
        assertThat(limiter.trackedClients()).isZero();
    }

    @Test
    void sweepsIdleClientsOnAcquire() throws InterruptedException {
        // 每次申请都尝试清理（间隔 0），空闲阈值 1 秒。
        RateLimiter limiter = new RateLimiter(properties(), 0L, 1L);

        limiter.acquire("10.0.0.1");
        limiter.acquire("10.0.0.2");
        assertThat(limiter.trackedClients()).isEqualTo(2);

        Thread.sleep(1100);
        limiter.acquire("10.0.0.3");

        // 1、2 已空闲超过 1 秒被清掉，只剩刚创建的 3。
        assertThat(limiter.trackedClients()).isEqualTo(1);
    }

    @Test
    void doesNotSweepBeforeTheIntervalElapses() {
        // 间隔 1 小时：任何一次申请都不该触发清理。
        RateLimiter limiter = new RateLimiter(properties(), 3_600_000_000_000L, 0L);

        limiter.acquire("10.0.0.1");
        limiter.acquire("10.0.0.2");
        assertThat(limiter.trackedClients()).isEqualTo(2);
    }

    @Test
    void treatsMissingClientKeyAsOneBucket() {
        RateLimiter limiter = limiter(0.2, 1, 1000, 1000);

        assertThat(limiter.acquire(null).allowed()).isTrue();
        assertThat(limiter.acquire("").allowed()).isFalse();
        assertThat(limiter.trackedClients()).isEqualTo(1);
    }
}
