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

        // 1. Kiểm tra cache còn fresh theo normalizedName
        Optional<PlaceCache> fresh = placeCacheService.findFreshCache(normalized);
        if (fresh.isPresent()) {
            log.debug("Place cache hit: '{}'", normalized);
            return fresh;
        }

        // 2. Goong geocode — bắt buộc để có lat/lng
        String geocodeQuery = buildGeocodeQuery(cleanedName, destination);
        Optional<GoongClient.GeocodeResult> geoOpt = goongClient.forwardGeocode(geocodeQuery);
        if (geoOpt.isEmpty()) {
            log.debug("Goong geocode: không tìm thấy '{}'", geocodeQuery);
            return Optional.empty();
        }

        GoongClient.GeocodeResult geo = geoOpt.get();

        // 3. Dedup theo goongPlaceId — nếu đã có row khác với cùng placeId thì tái dùng
        //    (chỉ tiết kiệm SerpApi khi row đã enrich đủ; nếu chưa, vẫn cần retry SerpApi)
        if (geo.placeId() != null && !geo.placeId().isBlank()) {
            Optional<PlaceCache> byPlaceId = placeCacheRepository.findByGoongPlaceId(geo.placeId());
            if (byPlaceId.isPresent() && byPlaceId.get().isSerpEnriched()) {
                log.debug("Place cache dedup by goongPlaceId='{}', reuse id={}", geo.placeId(), byPlaceId.get().getId());
                return byPlaceId;
            }
        }

        // 4. Lấy row cũ theo normalizedName nếu có (để update thay vì insert mới)
        Optional<PlaceCache> existing = placeCacheRepository.findByNormalizedName(normalized);
        // Hoặc lấy row theo goongPlaceId (trường hợp tên AI sinh khác nhau cho cùng địa điểm)
        if (existing.isEmpty() && geo.placeId() != null && !geo.placeId().isBlank()) {
            existing = placeCacheRepository.findByGoongPlaceId(geo.placeId());
        }

        PlaceCache.PlaceCacheBuilder builder = existing
                .map(PlaceCache::toBuilder)
                .orElseGet(() -> PlaceCache.builder().normalizedName(normalized).name(cleanedName));

        builder.latitude(geo.lat())
                .longitude(geo.lng())
                .goongPlaceId(geo.placeId())
                .address(geo.formattedAddress())
                .lastSyncedAt(LocalDateTime.now());

        // 5. SerpApi enrich (best-effort: nếu fail vẫn lưu dữ liệu Goong)
        SerpEnrichOutcome outcome = enrichWithSerpApi(builder, cleanedName, destination, placeName);
        builder.serpEnriched(outcome.enriched());
        builder.serpSyncedAt(outcome.syncedAt());

        PlaceCache saved = placeCacheRepository.save(builder.build());
        log.debug("Saved place cache id={} normalized='{}' serpEnriched={}", saved.getId(), normalized, outcome.enriched());
        return Optional.of(saved);
    }

    /** Kết quả của 1 lần attempt SerpApi. */
    private record SerpEnrichOutcome(boolean enriched, LocalDateTime syncedAt) {}

    /**
     * Gọi SerpApi và set các field enrich lên builder.
     * - enriched=true khi có rating HOẶC photos không rỗng → cache OK trong TTL
     * - enriched=false + syncedAt=now() khi attempt được nhưng không có data → áp dụng backoff
     * - enriched=false + syncedAt=null khi exception (chưa thật sự attempt được) → retry ngay lần sau
     */
    private SerpEnrichOutcome enrichWithSerpApi(PlaceCache.PlaceCacheBuilder builder,
                                                String cleanedName,
                                                String destination,
                                                String originalName) {
        try {
            String serpQuery = cleanedName + (destination != null ? " " + destination : "") + " Việt Nam";
            Optional<SerpApiClient.PlaceData> serpOpt = serpApiClient.searchPlace(serpQuery);
            if (serpOpt.isEmpty()) {
                log.debug("SerpApi không trả về data cho '{}', áp dụng backoff", serpQuery);
                return new SerpEnrichOutcome(false, LocalDateTime.now());
            }

            SerpApiClient.PlaceData s = serpOpt.get();
            builder.serpDataId(s.dataId())
                    .rating(s.rating())
                    .reviewCount(s.reviewCount())
                    .openingHoursJson(s.openingHoursJson())
                    .openState(s.openNow() != null ? (s.openNow() ? "OPEN" : "CLOSED") : null)
                    .phone(s.phone())
                    .website(s.website());

            List<Map<String, String>> photos = List.of();
            if (s.dataId() != null && !s.dataId().isBlank()) {
                photos = safeFetchPhotos(s.dataId());
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

            List<Map<String, Object>> reviews = List.of();
            if (s.dataId() != null && !s.dataId().isBlank()) {
                reviews = safeFetchReviews(s.dataId());
            }
            if (reviews != null && !reviews.isEmpty()) {
                try {
                    builder.reviewsJson(objectMapper.writeValueAsString(reviews));
                } catch (Exception ex) {
                    log.warn("Failed to serialize reviews list to JSON: {}", ex.getMessage());
                }
            }

            boolean hasPhotos = (photos != null && !photos.isEmpty()) || s.thumbnailUrl() != null;
            boolean enriched = s.rating() != null || hasPhotos;

            log.info("SerpApi enrich result: name='{}', rating={}, reviewCount={}, dataId={}, photosCount={}, reviewsCount={}, enriched={}",
                    cleanedName, s.rating(), s.reviewCount(), s.dataId(),
                    photos != null ? photos.size() : 0,
                    reviews != null ? reviews.size() : 0,
                    enriched);

            return new SerpEnrichOutcome(enriched, LocalDateTime.now());

        } catch (Exception e) {
            log.warn("SerpApi enrich failed for '{}', dùng Goong-only data: {}", originalName, e.getMessage());
            return new SerpEnrichOutcome(false, null);
        }
    }

    private List<Map<String, String>> safeFetchPhotos(String dataId) {
        try {
            return serpApiClient.fetchPhotos(dataId);
        } catch (Exception ex) {
            log.warn("SerpApi fetchPhotos failed for dataId='{}': {}", dataId, ex.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> safeFetchReviews(String dataId) {
        try {
            return serpApiClient.fetchReviews(dataId);
        } catch (Exception ex) {
            log.warn("SerpApi fetchReviews failed for dataId='{}': {}", dataId, ex.getMessage());
            return List.of();
        }
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
