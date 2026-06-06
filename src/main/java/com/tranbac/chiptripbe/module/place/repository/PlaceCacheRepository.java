package com.tranbac.chiptripbe.module.place.repository;

import com.tranbac.chiptripbe.module.place.entity.PlaceCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlaceCacheRepository extends JpaRepository<PlaceCache, Long> {
    Optional<PlaceCache> findByNormalizedName(String normalizedName);

    Optional<PlaceCache> findByGoongPlaceId(String goongPlaceId);
}