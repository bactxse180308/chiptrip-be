package com.tranbac.chiptripbe.module.place.service.impl;

import com.tranbac.chiptripbe.common.config.SerpApiProperties;
import com.tranbac.chiptripbe.module.place.entity.PlaceCache;
import com.tranbac.chiptripbe.module.place.repository.PlaceCacheRepository;
import com.tranbac.chiptripbe.module.place.service.PlaceCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
class PlaceCacheServiceImpl implements PlaceCacheService {

    private final PlaceCacheRepository repository;
    private final SerpApiProperties serpApiProperties;

    @Override
    @Transactional(readOnly = true)
    public Optional<PlaceCache> findFreshCache(String normalizedName, String normalizedDestination) {
        if (normalizedName == null || normalizedName.isBlank()) return Optional.empty();

        Optional<PlaceCache> match = (normalizedDestination == null || normalizedDestination.isBlank())
                ? repository.findFirstByNormalizedNameAndNormalizedDestinationIsNull(normalizedName)
                : repository.findFirstByNormalizedNameAndNormalizedDestination(normalizedName, normalizedDestination);

        return match.filter(this::isFresh);
    }

    /**
     * Lưu cache trong transaction RIÊNG: nếu DB-level unique index trên goong_place_id
     * reject duplicate (race condition giữa 2 request), exception chỉ rollback tx này —
     * tx ngoài (vd trip generation) vẫn an toàn.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PlaceCache save(PlaceCache place) {
        return repository.save(place);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PlaceCache refreshLastSyncedAt(PlaceCache place, LocalDateTime lastSyncedAt) {
        if (place == null) return null;
        if (place.getId() == null) {
            place.setLastSyncedAt(lastSyncedAt);
            return repository.save(place);
        }

        PlaceCache current = repository.findById(place.getId()).orElseGet(() -> repository.save(place));
        current.setLastSyncedAt(lastSyncedAt);
        return current;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PlaceCache saveAccommodationEnrichment(PlaceCache place,
                                                  String bookingUrl,
                                                  Long pricePerNightVnd,
                                                  BigDecimal rating,
                                                  String photosJson,
                                                  String reviewsJson) {
        if (place == null) return null;
        if (place.getId() == null) return repository.save(place);

        PlaceCache current = repository.findById(place.getId()).orElseGet(() -> repository.save(place));
        if (bookingUrl != null && !bookingUrl.isBlank()) {
            current.setBookingUrl(bookingUrl);
        }
        if (pricePerNightVnd != null) {
            current.setPricePerNightVnd(pricePerNightVnd);
        }
        if (rating != null) {
            current.setRating(rating);
        }
        if (current.getPhotosJson() == null && photosJson != null) {
            current.setPhotosJson(photosJson);
        }
        if (current.getReviewsJson() == null && reviewsJson != null) {
            current.setReviewsJson(reviewsJson);
        }
        return current;
    }

    /**
     * Cache được coi là "fresh" khi:
     * - Goong (lastSyncedAt) còn trong TTL, VÀ
     * - SerpApi đã enrich đủ (serpEnriched == true) HOẶC vẫn còn trong khoảng backoff sau lần thử gần nhất.
     * Nếu Goong còn hạn nhưng SerpApi chưa enrich và đã quá backoff → coi như miss để retry SerpApi
     * (caller sẽ giữ data Goong cũ, chỉ gọi lại SerpApi — xem PlaceEnrichmentServiceImpl).
     */
    private boolean isFresh(PlaceCache place) {
        if (place.getLastSyncedAt() == null) return false;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime goongExpiry = now.minusDays(serpApiProperties.getCacheTtlDays());
        if (!place.getLastSyncedAt().isAfter(goongExpiry)) return false;

        if (place.isSerpEnriched()) return true;

        // Chưa enrich đủ — chỉ coi là fresh khi còn trong cửa sổ backoff (tránh thundering herd)
        if (place.getSerpSyncedAt() == null) return false;
        LocalDateTime backoffUntil = place.getSerpSyncedAt().plusMinutes(serpApiProperties.getRetryBackoffMinutes());
        return now.isBefore(backoffUntil);
    }
}
