package com.tranbac.chiptripbe.module.geocoding.service.impl;

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
        if (destination == null || destination.isBlank()) return query + ", Việt Nam";
        if (query.toLowerCase().contains(destination.toLowerCase())) return query + ", Việt Nam";
        return query + ", " + destination + ", Việt Nam";
    }
}