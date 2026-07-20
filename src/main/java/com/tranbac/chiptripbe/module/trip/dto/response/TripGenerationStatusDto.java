package com.tranbac.chiptripbe.module.trip.dto.response;

/**
 * Trạng thái job sinh lịch trình gần nhất của user. Endpoint trạng thái giúp FE khôi phục màn hình
 * chờ sau khi F5/đổi route và lấy lại kết quả nếu WebSocket best-effort bị thất lạc.
 */
public record TripGenerationStatusDto(
        String generationId,
        String status,              // "IDLE" | "RUNNING" | "DONE" | "FAILED"
        Long tripId,
        Integer geocodeFailedCount,
        String error
) {
    public static TripGenerationStatusDto idle() {
        return new TripGenerationStatusDto(null, "IDLE", null, null, null);
    }

    public static TripGenerationStatusDto running(String generationId) {
        return new TripGenerationStatusDto(generationId, "RUNNING", null, null, null);
    }

    public static TripGenerationStatusDto fromResult(TripGenerationResultDto result) {
        return new TripGenerationStatusDto(
                result.generationId(), result.status(), result.tripId(),
                result.geocodeFailedCount(), result.error());
    }
}
