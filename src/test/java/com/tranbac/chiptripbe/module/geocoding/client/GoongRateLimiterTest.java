package com.tranbac.chiptripbe.module.geocoding.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoongRateLimiterTest {

    @Test
    void fivePermitsAtFourRequestsPerSecondSpanOneSecondWithoutBurst() {
        AtomicLong now = new AtomicLong();
        AtomicLong slept = new AtomicLong();
        GoongRateLimiter limiter = new GoongRateLimiter(4.0, now::get, nanos -> {
            slept.addAndGet(nanos);
            now.addAndGet(nanos);
        });

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.acquire(Duration.ofSeconds(1)));
        }

        assertEquals(1_000_000_000L, slept.get());
    }

    @Test
    void permitBeyondMaxWaitFailsFastWithoutCreatingBacklog() {
        AtomicLong now = new AtomicLong();
        AtomicLong slept = new AtomicLong();
        GoongRateLimiter limiter = new GoongRateLimiter(4.0, now::get, nanos -> {
            slept.addAndGet(nanos);
            now.addAndGet(nanos);
        });

        assertTrue(limiter.acquire(Duration.ZERO));
        assertFalse(limiter.acquire(Duration.ofMillis(100)));
        assertEquals(0L, slept.get());
    }

    @Test
    void delayedWakeupDoesNotBunchTheNextPermit() {
        AtomicLong now = new AtomicLong();
        long interval = 250_000_000L;
        GoongRateLimiter limiter = new GoongRateLimiter(4.0, now::get,
                nanos -> now.addAndGet(nanos + 300_000_000L));
        List<Long> emittedAt = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.acquire(Duration.ofSeconds(1)));
            emittedAt.add(now.get());
        }

        assertTrue(emittedAt.get(1) - emittedAt.get(0) >= interval);
        assertTrue(emittedAt.get(2) - emittedAt.get(1) >= interval);
    }
}
