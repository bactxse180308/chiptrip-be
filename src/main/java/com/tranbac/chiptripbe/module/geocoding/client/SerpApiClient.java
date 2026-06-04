package com.tranbac.chiptripbe.module.geocoding.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.common.config.SerpApiProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client gọi SerpApi (Google Maps engine) để enrich dữ liệu địa điểm:
 * rating, số review, giờ mở cửa, ảnh thumbnail, phone, website.
 *
 * Chỉ gọi API khi app.serpapi.enabled=true và có api-key.
 * Nếu SerpApi fail, caller vẫn tiếp tục với dữ liệu từ Goong.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SerpApiClient {

    private final WebClient.Builder webClientBuilder;
    private final SerpApiProperties properties;
    private final ObjectMapper objectMapper;

    private WebClient client;

    @PostConstruct
    void init() {
        client = webClientBuilder.baseUrl(properties.getBaseUrl()).build();
    }

    public record PlaceData(
            String dataId,
            BigDecimal rating,
            Integer reviewCount,
            String openingHoursJson,
            Boolean openNow,
            String thumbnailUrl,
            String phone,
            String website
    ) {}

    /**
     * Tìm kiếm địa điểm bằng Google Maps Search Results API (engine=google_maps) để lấy place_id và data_id chính xác.
     */
    @SuppressWarnings("unchecked")
    public Optional<PlaceData> searchPlace(String query) {
        if (!properties.isEnabled()) return Optional.empty();
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) return Optional.empty();
        if (query == null || query.isBlank()) return Optional.empty();

        try {
            log.info("SerpApi searchPlace (engine=google_maps) for query='{}'", query);
            Map<String, Object> resp = client.get()
                    .uri(b -> b.path("/search")
                            .queryParam("engine", "google_maps")
                            .queryParam("type", "search")
                            .queryParam("q", query)
                            .queryParam("hl", "vi")
                            .queryParam("api_key", properties.getApiKey())
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .block();

            if (resp == null) return Optional.empty();

            // Lấy từ local_results (dạng List) hoặc place_results (dạng Map nếu khớp chính xác 1 địa điểm)
            Object localResultsObj = resp.get("local_results");
            if (localResultsObj instanceof List) {
                List<Map<String, Object>> places = (List<Map<String, Object>>) localResultsObj;
                if (!places.isEmpty()) {
                    log.debug("Found place in local_results for query='{}'", query);
                    Map<String, Object> firstPlace = places.get(0);
                    return parsePlaceData(firstPlace);
                }
            }

            Object placeResultsObj = resp.get("place_results");
            if (placeResultsObj instanceof Map) {
                log.debug("Found place in place_results directly for query='{}'", query);
                return parsePlaceData((Map<String, Object>) placeResultsObj);
            }

            return Optional.empty();

        } catch (Exception e) {
            log.warn("SerpApi search failed for query='{}': {}", query, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Lấy danh sách ảnh chi tiết từ SerpApi (google_maps_photos engine).
     * Giới hạn tối đa 5 ảnh để tiết kiệm.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, String>> fetchPhotos(String dataId) {
        if (!properties.isEnabled()) return List.of();
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) return List.of();
        if (dataId == null || dataId.isBlank()) return List.of();

        try {
            log.info("SerpApi fetching photos for dataId='{}'", dataId);
            Map<String, Object> resp = client.get()
                    .uri(b -> b.path("/search")
                            .queryParam("engine", "google_maps_photos")
                            .queryParam("data_id", dataId)
                            .queryParam("api_key", properties.getApiKey())
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .block();

            if (resp == null) return List.of();

            List<Map<String, Object>> photosList = (List<Map<String, Object>>) resp.get("photos");
            if (photosList == null || photosList.isEmpty()) return List.of();

            return photosList.stream()
                    .map(p -> {
                        String photoUrl = (String) p.get("photo_url");
                        String thumbnailUrl = (String) p.get("thumbnail_url");
                        if (photoUrl == null) photoUrl = (String) p.get("image");
                        if (thumbnailUrl == null) thumbnailUrl = photoUrl;
                        if (photoUrl != null) {
                            return Map.of("url", photoUrl, "thumbnail", thumbnailUrl);
                        }
                        return null;
                    })
                    .filter(java.util.Objects::nonNull)
                    .limit(5) // Giới hạn tối đa 5 ảnh để tiết kiệm
                    .toList();

        } catch (Exception e) {
            log.warn("SerpApi photos fetch failed for dataId='{}': {}", dataId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Lấy danh sách đánh giá thật từ SerpApi (google_maps_reviews engine).
     * Giới hạn lấy tối đa 3 đánh giá.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchReviews(String dataId) {
        if (!properties.isEnabled()) return List.of();
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) return List.of();
        if (dataId == null || dataId.isBlank()) return List.of();

        try {
            log.info("SerpApi fetching reviews for dataId='{}'", dataId);
            Map<String, Object> resp = client.get()
                    .uri(b -> {
                        var builder = b.path("/search")
                                .queryParam("engine", "google_maps_reviews")
                                .queryParam("api_key", properties.getApiKey());
                        if (dataId.startsWith("ChI")) {
                            builder.queryParam("place_id", dataId);
                        } else {
                            builder.queryParam("data_id", dataId);
                        }
                        return builder.build();
                    })
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .block();

            if (resp == null) return List.of();

            List<Map<String, Object>> reviewsList = (List<Map<String, Object>>) resp.get("reviews");
            if (reviewsList == null || reviewsList.isEmpty()) return List.of();

            return reviewsList.stream()
                    .map(r -> {
                        Map<String, Object> user = (Map<String, Object>) r.get("user");
                        String author = user != null ? (String) user.get("name") : "Ẩn danh";
                        String avatar = user != null ? (String) user.get("thumbnail") : "";

                        BigDecimal rating = null;
                        Object ratingObj = r.get("rating");
                        if (ratingObj instanceof Number) {
                            rating = BigDecimal.valueOf(((Number) ratingObj).doubleValue());
                        }

                        String time = (String) r.get("date");
                        String text = (String) r.get("snippet");
                        if (text == null) text = (String) r.get("text");

                        if (text != null && !text.isBlank()) {
                            Map<String, Object> map = new java.util.HashMap<>();
                            map.put("author", author);
                            map.put("avatar", avatar != null ? avatar : "");
                            map.put("rating", rating != null ? rating : BigDecimal.valueOf(5));
                            map.put("time", time != null ? time : "Vừa xong");
                            map.put("text", text);
                            return map;
                        }
                        return null;
                    })
                    .filter(java.util.Objects::nonNull)
                    .limit(3) // Lấy tối đa 3 đánh giá thực tế
                    .toList();

        } catch (Exception e) {
            log.warn("SerpApi reviews fetch failed for dataId='{}': {}", dataId, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<PlaceData> parsePlaceData(Map<String, Object> p) {
        try {
            // Lấy ID: ưu tiên data_id, place_id, cid, hoặc lsig
            String dataId = p.get("data_id") != null ? p.get("data_id").toString() : null;
            if (dataId == null || dataId.isBlank()) dataId = p.get("place_id") != null ? p.get("place_id").toString() : null;
            if (dataId == null || dataId.isBlank()) dataId = p.get("cid") != null ? p.get("cid").toString() : null;
            if (dataId == null || dataId.isBlank()) dataId = p.get("lsig") != null ? p.get("lsig").toString() : null;

            BigDecimal rating = null;
            Object ratingObj = p.get("rating");
            if (ratingObj instanceof Number) {
                rating = BigDecimal.valueOf(((Number) ratingObj).doubleValue());
            } else if (ratingObj instanceof String) {
                try {
                    rating = new BigDecimal((String) ratingObj);
                } catch (Exception ignored) {}
            }

            Integer reviewCount = null;
            Object reviewObj = p.get("reviews");
            Object reviewsCountObj = p.get("reviews_count");
            Object reviewsOriginalObj = p.get("reviews_original");

            if (reviewsCountObj instanceof Number) {
                reviewCount = ((Number) reviewsCountObj).intValue();
            } else if (reviewsCountObj instanceof String) {
                try {
                    reviewCount = Integer.parseInt(((String) reviewsCountObj).replaceAll("[^0-9]", ""));
                } catch (Exception ignored) {}
            }

            if (reviewCount == null) {
                if (reviewObj instanceof Number) {
                    reviewCount = ((Number) reviewObj).intValue();
                } else if (reviewObj instanceof String) {
                    try {
                        String cleanedReview = ((String) reviewObj).replaceAll("[^0-9]", "");
                        reviewCount = Integer.parseInt(cleanedReview);
                    } catch (Exception ignored) {}
                }
            }

            if (reviewCount == null && reviewsOriginalObj instanceof String) {
                try {
                    String orig = (String) reviewsOriginalObj;
                    if (orig.contains("K") || orig.contains("k")) {
                        String numStr = orig.replaceAll("[^0-9.]", "");
                        double num = Double.parseDouble(numStr);
                        reviewCount = (int) (num * 1000);
                    } else {
                        reviewCount = Integer.parseInt(orig.replaceAll("[^0-9]", ""));
                    }
                } catch (Exception ignored) {}
            }

            String thumbnail = p.get("thumbnail") != null ? p.get("thumbnail").toString() : null;
            String phone = p.get("phone") != null ? p.get("phone").toString() : null;
            String website = p.get("website") != null ? p.get("website").toString() : null;
            String address = p.get("address") != null ? p.get("address").toString() : null;

            Boolean openNow = null;
            String openingHoursJson = null;
            Object hoursObj = p.get("hours");
            if (hoursObj instanceof Map) {
                Map<String, Object> hours = (Map<String, Object>) hoursObj;
                Object openNowObj = hours.get("open_now");
                if (openNowObj instanceof Boolean) openNow = (Boolean) openNowObj;
                List<?> schedule = (List<?>) hours.get("schedule");
                if (schedule != null) {
                    openingHoursJson = objectMapper.writeValueAsString(schedule);
                }
            }

            return Optional.of(new PlaceData(dataId, rating, reviewCount, openingHoursJson, openNow, thumbnail, phone, website));

        } catch (Exception e) {
            log.debug("Failed to parse SerpApi place data: {}", e.getMessage());
            return Optional.empty();
        }
    }
}