package com.tranbac.chiptripbe.module.trip.service.impl;

import com.tranbac.chiptripbe.module.trip.dto.response.TripGenerationResultDto;
import com.tranbac.chiptripbe.module.trip.dto.response.TripGenerationStatusDto;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bộ nhớ trạng thái của job generate async theo user.
 *
 * <p>Kết quả terminal được giữ một khoảng ngắn để client reconnect/poll lấy lại nếu bỏ lỡ WS.
 * Dự án hiện chạy một backend instance, giống giới hạn hiện tại của {@link TripGenerationLock}.</p>
 */
@Component
public class TripGenerationStatusTracker {

    private static final Duration TERMINAL_STATUS_TTL = Duration.ofMinutes(15);

    private final Map<Long, StatusEntry> statuses = new ConcurrentHashMap<>();

    public void markRunning(Long userId, String generationId) {
        statuses.put(userId, new StatusEntry(TripGenerationStatusDto.running(generationId), null));
    }

    public void complete(Long userId, TripGenerationResultDto result) {
        statuses.put(userId, new StatusEntry(
                TripGenerationStatusDto.fromResult(result),
                Instant.now().plus(TERMINAL_STATUS_TTL)));
    }

    public TripGenerationStatusDto getStatus(Long userId) {
        StatusEntry entry = statuses.get(userId);
        if (entry == null) return TripGenerationStatusDto.idle();

        if (entry.expiresAt() != null && !entry.expiresAt().isAfter(Instant.now())) {
            statuses.remove(userId, entry);
            return TripGenerationStatusDto.idle();
        }
        return entry.status();
    }

    public void clear(Long userId) {
        statuses.remove(userId);
    }

    @Scheduled(fixedDelay = 600_000)
    void evictExpiredStatuses() {
        Instant now = Instant.now();
        statuses.entrySet().removeIf(entry ->
                entry.getValue().expiresAt() != null
                        && !entry.getValue().expiresAt().isAfter(now));
    }

    private record StatusEntry(TripGenerationStatusDto status, Instant expiresAt) {}
}
