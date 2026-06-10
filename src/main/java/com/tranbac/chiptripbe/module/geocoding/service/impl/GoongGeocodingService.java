package com.tranbac.chiptripbe.module.geocoding.service.impl;

import com.tranbac.chiptripbe.common.util.PlaceQueryUtil;
import com.tranbac.chiptripbe.module.geocoding.client.GoongClient;
import com.tranbac.chiptripbe.module.geocoding.dto.GeocodingResult;
import com.tranbac.chiptripbe.module.geocoding.service.GeocodingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Triển khai GeocodingService dùng Goong API thay vì Google Maps.
 * Được dùng bởi PlaceEnrichmentService — không gọi trực tiếp từ TripService.
 */
@Service
@RequiredArgsConstructor
class GoongGeocodingService implements GeocodingService {

    private final GoongClient goongClient;

    @Override
    public GeocodingResult searchPlace(String query, String destination) {
        if (query == null || query.isBlank()) return null;

        String fullQuery = buildQuery(query, destination);

        return goongClient.forwardGeocode(fullQuery)
                .map(geo -> new GeocodingResult(
                        geo.placeId(),
                        null,
                        geo.formattedAddress(),
                        geo.lat(),
                        geo.lng(),
                        "goong"
                ))
                .orElse(null);
    }

    private String buildQuery(String query, String destination) {
        // AI đã được instruct để đưa city vào searchQuery, không append destination trip nữa
        // (tránh "Sân bay Nội Bài Hà Nội, Đà Lạt, Việt Nam" — Goong tìm nhầm thành phố).
        // destination param giữ lại để giữ tương thích interface nhưng không sử dụng.
        return PlaceQueryUtil.buildPlaceQuery(query);
    }
}