package com.tranbac.chiptripbe.module.place.service;

import com.tranbac.chiptripbe.module.place.entity.PlaceCache;

import java.util.Optional;

public interface PlaceEnrichmentService {

    /**
     * Trả PlaceCache đã enrich đầy đủ (lat/lng từ Goong, rating/ảnh/giờ từ SerpApi).
     *
     * Logic cache:
     * - Có cache và còn mới → trả ngay từ DB.
     * - Cache quá cũ hoặc chưa có → gọi Goong + SerpApi, lưu DB, trả kết quả mới.
     *
     * Nếu SerpApi fail, vẫn trả PlaceCache với dữ liệu Goong (lat/lng/address).
     * Trả Optional.empty() nếu Goong không geocode được địa điểm.
     */
    Optional<PlaceCache> resolvePlace(String placeName, String destination);
}