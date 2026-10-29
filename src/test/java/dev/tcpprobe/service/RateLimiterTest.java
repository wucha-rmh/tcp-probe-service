package dev.tcpprobe.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    private final AtomicLong nanos = new AtomicLong(0);

    private RateLimiter limiter(double rps, int burst, double clientRps, int clientBurst) {
        return new RateLimiter(rps, burst, clientRps, clientBurst, nanos::get);
    }

    @Test
    void allowsBurstThenDenies() {
        RateLimiter limiter = limiter(1, 2, 1, 2);
        assertTrue(limiter.acquire("a").allowed());
        assertTrue(limiter.acquire("a").allowed());
        RateLimiter.Decision denied = limiter.acquire("a");
        assertFalse(denied.allowed());
        assertEquals("global", denied.limitReason(), "the shared bucket empties first");
        assertTrue(denied.retryAfterSeconds() >= 1);
    }

    @Test
    void refillsTokensOverTime() {
        RateLimiter limiter = limiter(1, 1, 1, 1);
        assertTrue(limiter.acquire("a").allowed());
        assertFalse(limiter.acquire("a").allowed());
        nanos.addAndGet(1_000_000_000L);
        assertTrue(limiter.acquire("a").allowed());
    }

    @Test
    void globalDenialLeavesClientBucketUntouched() {
        RateLimiter limiter = limiter(1, 1, 10, 10);
        assertTrue(limiter.acquire("a").allowed());
        RateLimiter.Decision denied = limiter.acquire("b");
        assertEquals("global", denied.limitReason());
        nanos.addAndGet(1_000_000_000L);
        RateLimiter.Decision allowed = limiter.acquire("b");
        assertTrue(allowed.allowed());
        assertEquals(9.0, allowed.remaining(), 1e-9, "client b never spent a token on the denied call");
    }

    @Test
    void deniesPerClientIndependently() {
        RateLimiter limiter = limiter(100, 100, 1, 1);
        assertTrue(limiter.acquire("a").allowed());
        RateLimiter.Decision denied = limiter.acquire("a");
        assertEquals("client", denied.limitReason());
        assertTrue(limiter.acquire("b").allowed(), "another client has its own budget");
    }

    @Test
    void sweepsIdleClientBuckets() {
        RateLimiter limiter = limiter(100, 100, 100, 100);
        limiter.acquire("a");
        limiter.acquire("b");
        assertEquals(2, limiter.trackedClients());
        nanos.addAndGet(700_000_000_000L);
        assertEquals(2, limiter.sweepIdle(600));
        assertEquals(0, limiter.trackedClients());
    }

    @Test
    void unknownClientKeyIsBucketedTogether() {
        RateLimiter limiter = limiter(100, 100, 1, 1);
        assertTrue(limiter.acquire(null).allowed());
        assertEquals("client", limiter.acquire("").limitReason());
    }
}
