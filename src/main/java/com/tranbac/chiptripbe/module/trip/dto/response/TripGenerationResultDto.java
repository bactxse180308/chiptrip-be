package com.tranbac.chiptripbe.module.trip.dto.response;

/**
 * Payload đẩy qua WebSocket (/user/queue/trip-generation) khi job sinh lịch trình bất đồng bộ
 * hoàn tất hoặc lỗi. FE đang đợi ở màn loading sẽ điều hướng sang kết quả (DONE) hoặc hiện lỗi (FAILED).
 */
public record TripGenerationResultDto(
        String status,              // "DONE" | "FAILED"
        Long tripId,                // null khi FAILED
        Integer geocodeFailedCount, // số địa điểm không định vị được (nullable)
        String error                // null khi DONE
) {
    public static TripGenerationResultDto done(Long tripId, Integer geocodeFailedCount) {
        return new TripGenerationResultDto("DONE", tripId, geocodeFailedCount, null);
    }

    public static TripGenerationResultDto failed(String error) {
        return new TripGenerationResultDto("FAILED", null, null, error);
    }
}
