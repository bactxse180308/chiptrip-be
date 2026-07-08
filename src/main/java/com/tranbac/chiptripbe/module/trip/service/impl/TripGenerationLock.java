package com.tranbac.chiptripbe.module.trip.service.impl;

import com.tranbac.chiptripbe.common.exception.AppException;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Khóa per-user chống chạy 2 job generate ĐỒNG THỜI — DÙNG CHUNG cho cả luồng sync
 * ({@code POST /trips/generate}) và async ({@code POST /trips/generate-async}).
 *
 * Vì sao cần: credit được trừ atomic lúc persist chỉ chống TRỪ TRÙNG, KHÔNG chống 2 request
 * cùng gọi AI/Goong/SerpApi TRƯỚC đó rồi 1 request thua bị rollback — vẫn tạo trùng trip +
 * đốt lượt gọi provider vô ích. Khóa này bảo đảm mỗi user chỉ có 1 job generate tại một thời điểm,
 * bất kể vào qua endpoint sync hay async.
 */
@Component
public class TripGenerationLock {

    private final Set<Long> inFlightUsers = ConcurrentHashMap.newKeySet();

    /** Đánh dấu user bắt đầu 1 job; ném 409 nếu đã có job generate khác đang chạy. */
    public void acquire(Long userId) {
        if (!inFlightUsers.add(userId)) {
            throw AppException.conflict("Bạn đang có một lịch trình đang được tạo. Vui lòng đợi hoàn tất.");
        }
    }

    /** Nhả khóa. Idempotent (remove không tồn tại là no-op). */
    public void release(Long userId) {
        inFlightUsers.remove(userId);
    }
}
