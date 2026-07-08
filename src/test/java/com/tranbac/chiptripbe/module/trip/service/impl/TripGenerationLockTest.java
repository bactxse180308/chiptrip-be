package com.tranbac.chiptripbe.module.trip.service.impl;

import com.tranbac.chiptripbe.common.exception.AppException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P1: khóa per-user dùng chung sync + async — request thứ 2 của cùng user bị chặn (409)
 * trước khi kịp gọi AI/place lần nữa.
 */
class TripGenerationLockTest {

    private final TripGenerationLock lock = new TripGenerationLock();

    @Test
    void acquire_secondConcurrentForSameUser_throwsConflict() {
        lock.acquire(1L);
        assertThrows(AppException.class, () -> lock.acquire(1L));
    }

    @Test
    void acquire_afterRelease_succeeds() {
        lock.acquire(1L);
        lock.release(1L);
        assertDoesNotThrow(() -> lock.acquire(1L));
    }

    @Test
    void acquire_differentUsers_independent() {
        lock.acquire(1L);
        assertDoesNotThrow(() -> lock.acquire(2L));
    }
}
