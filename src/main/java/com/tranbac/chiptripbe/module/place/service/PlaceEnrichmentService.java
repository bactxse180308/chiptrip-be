package com.tranbac.chiptripbe.module.place.service;

import com.tranbac.chiptripbe.module.place.entity.PlaceCache;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PlaceEnrichmentService {

    /**
     * Mốc GPS tham chiếu (destination/departure của trip) dùng để validate kết quả geocode
     * thay vì chỉ so chuỗi địa chỉ. provinceName từ Goong V2 compound, nullable.
     */
    record GeoAnchor(BigDecimal lat, BigDecimal lng, String provinceName) {}

    /**
     * Geocode 1 địa danh (destination/departure) thành anchor GPS. Cache in-memory theo tên.
     * Trả Optional.empty() nếu Goong fail hoặc kết quả không khớp tên (fail-soft —
     * caller truyền anchors rỗng thì validation degrade về so chuỗi địa chỉ như cũ).
     */
    Optional<GeoAnchor> geocodeAnchor(String locationName);

    /**
     * Key chuẩn hóa mà resolvePlace dùng để tra/ghi cache (bỏ dấu, strip tiền tố động từ).
     * Caller dùng để dedup các searchQuery cùng trỏ về 1 địa điểm trước khi fan-out song song.
     */
    String canonicalKey(String searchQuery);

    /**
     * Trả PlaceCache đã enrich đầy đủ (lat/lng từ Goong, rating/ảnh/giờ từ SerpApi).
     *
     * Logic cache:
     * - Có cache và còn mới → trả ngay từ DB.
     * - Cache quá cũ hoặc chưa có → gọi Goong + SerpApi, lưu DB, trả kết quả mới.
     *
     * Validate vùng: chấp nhận khi địa chỉ chứa destination, HOẶC cách 1 anchor ≤ 60km,
     * HOẶC trùng provinceName với anchor. Goong miss/mismatch → fallback SerpApi.
     *
     * Nếu SerpApi fail, vẫn trả PlaceCache với dữ liệu Goong (lat/lng/address).
     * deadline (nullable): quá hạn thì bỏ qua các external call còn lại (fail-soft).
     *
     * preferSerpIdentity: với POI là cơ sở kinh doanh (nhà hàng/điểm tham quan) đặt true —
     * resolve danh tính qua SerpApi trước (chính xác hơn Goong cho tên cụ thể; Goong hay gom
     * các tên không có trong gazetteer về cùng 1 toạ độ vùng), chỉ rơi về Goong khi SerpApi miss.
     * Với địa danh/giao thông (Goong tốt hơn) đặt false → giữ Goong-first như cũ.
     */
    Optional<PlaceCache> resolvePlace(String placeName, String destination,
                                      List<GeoAnchor> anchors, Instant deadline,
                                      boolean preferSerpIdentity);

    /**
     * Enrich thêm thông tin khách sạn (bookingUrl + pricePerNightVnd) từ SerpApi Google Hotels,
     * lưu trực tiếp vào PlaceCache hiện có. Best-effort: fail-soft, không vỡ luồng generate.
     *
     * Chỉ gọi khi activity type=ACCOMMODATION. deadline (nullable): quá hạn thì skip.
     */
    void enrichAccommodation(PlaceCache cache, LocalDate checkIn, LocalDate checkOut,
                             Integer adults, Instant deadline);
}
