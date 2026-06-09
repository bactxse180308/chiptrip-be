package com.tranbac.chiptripbe.module.place.service;

import com.tranbac.chiptripbe.module.place.entity.PlaceCache;

import java.time.LocalDate;
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

    /**
     * Enrich thêm thông tin khách sạn (bookingUrl + pricePerNightVnd) từ SerpApi Google Hotels,
     * lưu trực tiếp vào PlaceCache hiện có. Best-effort: fail-soft, không vỡ luồng generate.
     *
     * Chỉ gọi khi activity type=ACCOMMODATION.
     */
    void enrichAccommodation(PlaceCache cache, LocalDate checkIn, LocalDate checkOut, Integer adults);
}