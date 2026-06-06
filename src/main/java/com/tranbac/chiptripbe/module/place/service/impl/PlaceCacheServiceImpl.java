package com.tranbac.chiptripbe.module.place.service.impl;

import com.tranbac.chiptripbe.common.config.SerpApiProperties;
import com.tranbac.chiptripbe.module.place.entity.PlaceCache;
import com.tranbac.chiptripbe.module.place.repository.PlaceCacheRepository;
import com.tranbac.chiptripbe.module.place.service.PlaceCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
class PlaceCacheServiceImpl implements PlaceCacheService {

    private final PlaceCacheRepository repository;
    private final SerpApiProperties serpApiProperties;

    @Override
    @Transactional(readOnly = true)
    public Optional<PlaceCache> findFreshCache(String normalizedName) {
        return repository.findByNormalizedName(normalizedName)
                .filter(this::isFresh);
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