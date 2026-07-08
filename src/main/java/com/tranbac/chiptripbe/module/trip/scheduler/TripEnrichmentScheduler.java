package com.tranbac.chiptripbe.module.trip.scheduler;

import com.tranbac.chiptripbe.module.trip.service.TripService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Durable safety-net cho enrich nền (P1). Enrich FULL ngày 2..N vốn submit vào executor in-memory
 * (best-effort) → mất khi app restart / queue DiscardPolicy / provider down lâu hơn retry ngắn.
 *
 * Scheduler này quét định kỳ các trip còn {@code enrichmentPending} và enrich lại từ trạng thái DB,
 * tắt cờ khi xong hoặc hết số lần thử. Nhờ vậy cờ {@code enriching} (FE dựa vào để poll) luôn tiến
 * tới false, và ảnh/review vẫn "nảy" ra dù job nền ban đầu đã chết.
 *
 * MVP 1 instance: chưa cần ShedLock (giống PostTripScheduler / TripReminderScheduler). Nhiều instance
 * cần khóa phân tán để 2 instance không cùng sweep 1 trip.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TripEnrichmentScheduler {

    private final TripService tripService;

    /** Mỗi 5 phút: quét & enrich lại các trip còn pending mà job nền in-memory đã mất. */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 2 * 60 * 1000)
    public void sweep() {
        try {
            tripService.sweepPendingEnrichments();
        } catch (Exception e) {
            log.warn("TripEnrichmentScheduler sweep lỗi: {}", e.getMessage());
        }
    }
}
