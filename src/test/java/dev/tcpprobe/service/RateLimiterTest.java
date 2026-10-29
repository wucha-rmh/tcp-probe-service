package dev.tcpprobe.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private static RateLimiter limiter(double clientRps, int clientBurst, double globalRps, int globalBurst) {
        return new RateLimiter(new dev.tcpprobe.ProbeProperties(
                2000, 2000, 8, 200, 16, 2, 600, 30000, 1048576,
                new dev.tcpprobe.ProbeProperties.RateLimit(globalRps, globalBurst, clientRps, clientBurst)));
    }

    @Test
    void allowsTheBurstThenDenies() {
        RateLimiter limiter = limiter(0.2, 2, 1000, 1000);

        assertThat(limiter.acquire("10.0.0.1").allowed()).isTrue();
        assertThat(limiter.acquire("10.0.0.1").allowed()).isTrue();

        RateLimiter.Decision denied = limiter.acquire("10.0.0.1");
        assertThat(denied.allowed()).isFalse();
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
    void treatsMissingClientKeyAsOneBucket() {
        RateLimiter limiter = limiter(0.2, 1, 1000, 1000);

        assertThat(limiter.acquire(null).allowed()).isTrue();
        assertThat(limiter.acquire("").allowed()).isFalse();
        assertThat(limiter.trackedClients()).isEqualTo(1);
    }
}
