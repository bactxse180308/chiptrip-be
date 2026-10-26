package com.tranbac.chiptripbe.module.trip.scheduler;

import com.tranbac.chiptripbe.module.trip.service.TripEnrichmentMaintenance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Durable safety-net cho enrich nền (P1). Enrich FULL ngày 2..N vốn submit vào executor in-memory
 * (best-effort) → mất khi app restart / queue DiscardPolicy / provider down kéo dài.
 *
 * Scheduler này quét định kỳ các trip còn {@code enrichmentPending} và enrich lại từ trạng thái DB,
 * chỉ tắt cờ khi xong. Lỗi tạm thời được hẹn lại theo cooldown/exponential backoff, nên cờ
 * {@code enriching} không báo hoàn tất giả khi địa điểm vẫn chưa được xử lý.
 *
 * Mỗi trip được claim nguyên tử tại DB trước external call nên nhiều worker không xử lý trùng row.
 * Nếu scale nhiều backend, Goong limiter vẫn cần chuyển sang limiter phân tán để giữ tổng rate/IP.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TripEnrichmentScheduler {

    private final TripEnrichmentMaintenance tripEnrichmentMaintenance;

    /** Mỗi 5 phút: quét & enrich lại các trip còn pending mà job nền in-memory đã mất. */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 2 * 60 * 1000)
    public void sweep() {
        try {
            tripEnrichmentMaintenance.sweepPendingEnrichments();
        } catch (Exception e) {
            log.warn("TripEnrichmentScheduler sweep lỗi: {}", e.getMessage());
        }
    }
}
