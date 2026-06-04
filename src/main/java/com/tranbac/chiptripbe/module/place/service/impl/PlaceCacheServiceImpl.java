package com.tranbac.chiptripbe.module.place.service.impl;

import com.tranbac.chiptripbe.common.config.SerpApiProperties;
import com.tranbac.chiptripbe.module.place.entity.PlaceCache;
import com.tranbac.chiptripbe.module.place.repository.PlaceCacheRepository;
import com.tranbac.chiptripbe.module.place.service.PlaceCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
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

    @Override
    @Transactional
    public PlaceCache save(PlaceCache place) {
        return repository.save(place);
    }

    private boolean isFresh(PlaceCache place) {
        if (place.getLastSyncedAt() == null) return false;
        LocalDateTime expiry = LocalDateTime.now().minusDays(serpApiProperties.getCacheTtlDays());
        return place.getLastSyncedAt().isAfter(expiry);
    }
}