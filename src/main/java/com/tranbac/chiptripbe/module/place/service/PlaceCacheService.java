package com.tranbac.chiptripbe.module.place.service;

import com.tranbac.chiptripbe.module.place.entity.PlaceCache;

import java.util.Optional;

public interface PlaceCacheService {

    /**
     * Tìm cache còn mới theo normalizedName.
     * Trả Optional.empty() nếu không có hoặc cache đã quá ttlDays ngày.
     */
    Optional<PlaceCache> findFreshCache(String normalizedName);

    PlaceCache save(PlaceCache place);
}