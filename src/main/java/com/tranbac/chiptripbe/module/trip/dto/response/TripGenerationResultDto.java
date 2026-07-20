package com.tranbac.chiptripbe.module.trip.dto.response;

/**
 * Payload đẩy qua WebSocket (/user/queue/trip-generation) khi job sinh lịch trình bất đồng bộ
 * hoàn tất hoặc lỗi. FE đang đợi ở màn loading sẽ điều hướng sang kết quả (DONE) hoặc hiện lỗi (FAILED).
 */
public record TripGenerationResultDto(
        String generationId,        // client-generated id để không nhận nhầm kết quả của job cũ
        String status,              // "DONE" | "FAILED"
        Long tripId,                // null khi FAILED
        Integer geocodeFailedCount, // số địa điểm không định vị được (nullable)
        String error                // null khi DONE
) {
    public static TripGenerationResultDto done(String generationId, Long tripId, Integer geocodeFailedCount) {
        return new TripGenerationResultDto(generationId, "DONE", tripId, geocodeFailedCount, null);
    }

    public static TripGenerationResultDto failed(String generationId, String error) {
        return new TripGenerationResultDto(generationId, "FAILED", null, null, error);
    }
}
