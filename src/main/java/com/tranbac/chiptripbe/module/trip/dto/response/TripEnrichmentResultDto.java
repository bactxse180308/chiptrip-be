package com.tranbac.chiptripbe.module.trip.dto.response;

/** Payload đẩy qua /user/queue/trip-enrichment khi enrichment nền đã kết thúc. */
public record TripEnrichmentResultDto(Long tripId, boolean enriching) {
    public static TripEnrichmentResultDto done(Long tripId) {
        return new TripEnrichmentResultDto(tripId, false);
    }
}
