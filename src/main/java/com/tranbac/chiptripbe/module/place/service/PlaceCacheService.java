package com.tranbac.chiptripbe.module.place.service;

import com.tranbac.chiptripbe.module.place.entity.PlaceCache;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

public interface PlaceCacheService {

    /**
     * Tìm cache còn mới theo (normalizedName, normalizedDestination).
     * Trả Optional.empty() nếu không có hoặc cache đã quá ttlDays ngày.
     */
    Optional<PlaceCache> findFreshCache(String normalizedName, String normalizedDestination);

    PlaceCache save(PlaceCache place);

    PlaceCache refreshLastSyncedAt(PlaceCache place, LocalDateTime lastSyncedAt);

    PlaceCache saveAccommodationEnrichment(PlaceCache place,
                                           String bookingUrl,
                                           Long pricePerNightVnd,
                                           BigDecimal rating,
                                           String photosJson,
                                           String reviewsJson);
}
