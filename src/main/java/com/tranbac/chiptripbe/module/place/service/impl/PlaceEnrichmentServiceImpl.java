package com.tranbac.chiptripbe.module.place.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.module.geocoding.client.GoongClient;
import com.tranbac.chiptripbe.module.geocoding.client.SerpApiClient;
import com.tranbac.chiptripbe.module.place.entity.PlaceCache;
import com.tranbac.chiptripbe.module.place.repository.PlaceCacheRepository;
import com.tranbac.chiptripbe.module.place.service.PlaceCacheService;
import com.tranbac.chiptripbe.module.place.service.PlaceEnrichmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
class PlaceEnrichmentServiceImpl implements PlaceEnrichmentService {

    private final PlaceCacheService placeCacheService;
    private final PlaceCacheRepository placeCacheRepository;
    private final GoongClient goongClient;
    private final SerpApiClient serpApiClient;
    private final ObjectMapper objectMapper;

    private String cleanPlaceName(String name) {
        if (name == null) return "";
        String regex = "(?i)^(tham quan|check[- ]in|checkin|an trua|an toi|an sang|an|uong cafe|uong|binh minh tai|san may|di|tha den hoa dang|mua sam tai|mua sam|vieng|trai nghiem|ngam hoang hon|trekking|loi bo|dao bo|thuong thuc|check in|uong cà phe|uong ca phe|an trưa|an tối|an sáng|ăn trưa|ăn tối|ăn sáng|ăn|uống cafe|uống cà phê|uống ca phe|uống|bình minh tại|săn mây|đi|thả đèn hoa đăng|mua sắm tại|mua sắm|viếng|trải nghiệm|ngắm hoàng hôn|thưởng thức)\\s+";
        String cleaned = name.replaceAll(regex, "").trim();
        if (cleaned.length() > 0) {
            cleaned = Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
        }
        return cleaned;
    }

    @Override
    @Transactional
    public Optional<PlaceCache> resolvePlace(String placeName, String destination) {
        if (placeName == null || placeName.isBlank()) return Optional.empty();

        String cleanedName = cleanPlaceName(placeName);
        String normalized = normalize(cleanedName);

        // 1. Kiểm tra cache còn mới
        Optional<PlaceCache> fresh = placeCacheService.findFreshCache(normalized);
        if (fresh.isPresent()) {
            log.debug("Place cache hit: '{}'", normalized);
            return fresh;
        }

        // 2. Goong geocode — đây là bước bắt buộc
        String geocodeQuery = buildGeocodeQuery(cleanedName, destination);
        Optional<GoongClient.GeocodeResult> geoOpt = goongClient.forwardGeocode(geocodeQuery);
        if (geoOpt.isEmpty()) {
            log.debug("Goong geocode: không tìm thấy '{}'", geocodeQuery);
            return Optional.empty();
        }

        GoongClient.GeocodeResult geo = geoOpt.get();

        // 3. Lấy row cũ nếu có (để update thay vì insert mới)
        Optional<PlaceCache> existing = placeCacheRepository.findByNormalizedName(normalized);
        PlaceCache.PlaceCacheBuilder builder = existing
                .map(PlaceCache::toBuilder)
                .orElse(PlaceCache.builder().normalizedName(normalized).name(cleanedName));

        builder.latitude(geo.lat())
                .longitude(geo.lng())
                .goongPlaceId(geo.placeId())
                .address(geo.formattedAddress())
                .lastSyncedAt(LocalDateTime.now());

        // 4. SerpApi enrich (best-effort: nếu fail vẫn lưu dữ liệu Goong)
        try {
            String serpQuery = cleanedName + (destination != null ? " " + destination : "") + " Việt Nam";
            Optional<SerpApiClient.PlaceData> serpOpt = serpApiClient.searchPlace(serpQuery);
            serpOpt.ifPresent(s -> {
                builder.serpDataId(s.dataId())
                        .rating(s.rating())
                        .reviewCount(s.reviewCount())
                        .openingHoursJson(s.openingHoursJson())
                        .openState(s.openNow() != null ? (s.openNow() ? "OPEN" : "CLOSED") : null)
                        .phone(s.phone())
                        .website(s.website());
                // Lấy nhiều ảnh từ SerpApi
                List<Map<String, String>> photos = List.of();
                if (s.dataId() != null && !s.dataId().isBlank()) {
                    try {
                        photos = serpApiClient.fetchPhotos(s.dataId());
                    } catch (Exception ex) {
                        log.warn("SerpApi fetchPhotos failed for dataId='{}': {}", s.dataId(), ex.getMessage());
                    }
                }

                if (photos != null && !photos.isEmpty()) {
                    try {
                        builder.photosJson(objectMapper.writeValueAsString(photos));
                    } catch (Exception ex) {
                        log.warn("Failed to serialize photos list to JSON: {}", ex.getMessage());
                        if (s.thumbnailUrl() != null) {
                            builder.photosJson(buildPhotosJson(s.thumbnailUrl()));
                        }
                    }
                } else if (s.thumbnailUrl() != null) {
                    builder.photosJson(buildPhotosJson(s.thumbnailUrl()));
                }

                // Lấy các bài đánh giá thật từ SerpApi
                List<Map<String, Object>> reviews = List.of();
                if (s.dataId() != null && !s.dataId().isBlank()) {
                    try {
                        reviews = serpApiClient.fetchReviews(s.dataId());
                    } catch (Exception ex) {
                        log.warn("SerpApi fetchReviews failed for dataId='{}': {}", s.dataId(), ex.getMessage());
                    }
                }

                if (reviews != null && !reviews.isEmpty()) {
                    try {
                        builder.reviewsJson(objectMapper.writeValueAsString(reviews));
                    } catch (Exception ex) {
                        log.warn("Failed to serialize reviews list to JSON: {}", ex.getMessage());
                    }
                }

                log.info("SerpApi successfully enriched place: name='{}', rating={}, reviewCount={}, dataId={}, photosCount={}, reviewsCount={}",
                        cleanedName, s.rating(), s.reviewCount(), s.dataId(),
                        photos != null ? photos.size() : 0,
                        reviews != null ? reviews.size() : 0);
            });
        } catch (Exception e) {
            log.warn("SerpApi enrich failed for '{}', dùng Goong-only data: {}", placeName, e.getMessage());
        }

        PlaceCache saved = placeCacheRepository.save(builder.build());
        log.debug("Saved place cache id={} normalized='{}'", saved.getId(), normalized);
        return Optional.of(saved);
    }

    // ─── Private helpers ────────────────────────────────────────────────────

    /**
     * Chuẩn hóa tên: bỏ dấu tiếng Việt, lowercase, trim khoảng trắng.
     * Dùng để tra cứu và tránh duplicate cache.
     */
    private String normalize(String name) {
        if (name == null) return "";
        return name.toLowerCase()
                .replaceAll("[àáạảãâầấậẩẫăằắặẳẵ]", "a")
                .replaceAll("[èéẹẻẽêềếệểễ]", "e")
                .replaceAll("[ìíịỉĩ]", "i")
                .replaceAll("[òóọỏõôồốộổỗơờớợởỡ]", "o")
                .replaceAll("[ùúụủũưừứựửữ]", "u")
                .replaceAll("[ỳýỵỷỹ]", "y")
                .replaceAll("[đ]", "d")
                .replaceAll("[^a-z0-9\\s]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String buildGeocodeQuery(String placeName, String destination) {
        if (destination == null || destination.isBlank()) return placeName + ", Việt Nam";
        if (placeName.toLowerCase().contains(destination.toLowerCase())) return placeName + ", Việt Nam";
        return placeName + ", " + destination + ", Việt Nam";
    }

    private String buildPhotosJson(String thumbnailUrl) {
        try {
            return objectMapper.writeValueAsString(List.of(Map.of("url", thumbnailUrl, "thumbnail", thumbnailUrl)));
        } catch (Exception e) {
            return null;
        }
    }
}