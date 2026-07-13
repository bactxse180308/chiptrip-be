package com.tranbac.chiptripbe.module.geocoding.client;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/** Pacer dùng chung cho mọi Goong operation trên một backend instance; không cho phép burst. */
final class GoongRateLimiter {

    @FunctionalInterface
    interface NanosSleeper {
        void sleep(long nanos) throws InterruptedException;
    }

    private final long intervalNanos;
    private final LongSupplier nanoTime;
    private final NanosSleeper sleeper;
    private final ReentrantLock lock = new ReentrantLock(true);
    private long nextPermitNanos;

    GoongRateLimiter(double requestsPerSecond) {
        this(requestsPerSecond, System::nanoTime,
                nanos -> TimeUnit.NANOSECONDS.sleep(nanos));
    }

    GoongRateLimiter(double requestsPerSecond, LongSupplier nanoTime, NanosSleeper sleeper) {
        this.intervalNanos = (long) (1_000_000_000D / Math.max(0.1D, requestsPerSecond));
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    boolean acquire(Duration maxWait) {
        long maxWaitNanos = Math.max(0, maxWait.toNanos());
        long deadlineNanos = nanoTime.getAsLong() + maxWaitNanos;
        boolean locked = false;
        try {
            locked = lock.tryLock(maxWaitNanos, TimeUnit.NANOSECONDS);
            if (!locked) return false;
            long now = nanoTime.getAsLong();
            long remainingBudgetNanos = Math.max(0, deadlineNanos - now);
            long waitNanos = Math.max(0, nextPermitNanos - now);
            if (waitNanos > remainingBudgetNanos) return false;
            if (waitNanos > 0) sleeper.sleep(waitNanos);
            nextPermitNanos = nanoTime.getAsLong() + intervalNanos;
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (locked) lock.unlock();
        }
    }
}
