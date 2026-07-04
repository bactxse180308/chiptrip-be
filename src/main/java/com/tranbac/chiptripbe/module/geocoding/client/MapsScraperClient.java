package com.tranbac.chiptripbe.module.geocoding.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.common.config.ScraperProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client gọi sidecar scrape Google Maps (fallback khi SerpApi hết quota).
 *
 * Sidecar trả JSON shape trùng SerpApiClient.PlaceData + reviews/photos/openingHours.
 * Mã lỗi là tín hiệu điều khiển: 503 = đang cooldown (throttle backoff) → BỎ ngay về Goong-only,
 * 502 = miss, 504 = timeout. Mọi lỗi → Optional.empty() (fail-soft, không bao giờ chặn generate).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MapsScraperClient {

    private final WebClient.Builder webClientBuilder;
    private final ScraperProperties properties;
    private final ObjectMapper objectMapper;

    private WebClient client;

    @PostConstruct
    void init() {
        client = webClientBuilder.clone()
                .baseUrl(properties.getBaseUrl())
                .build();
    }

    /** Địa điểm đã scrape — các JSON string map thẳng vào PlaceCache. */
    public record ScrapedPlace(
            boolean found,
            boolean degraded,
            String title,
            BigDecimal rating,
            Integer reviewCount,
            String placeType,
            String phone,
            String website,
            String openState,
            String hoursText,
            String openingHoursJson,
            String photosJson,
            String reviewsJson,
            BigDecimal latitude,
            BigDecimal longitude
    ) {}

    /**
     * Scrape 1 địa điểm. background=true → sidecar dùng gap giãn cách lớn hơn (cho enrich nền).
     * Trả empty khi: tắt, query rỗng, 503 cooldown, 502/504, connection refused, timeout, found=false.
     */
    @SuppressWarnings("unchecked")
    public Optional<ScrapedPlace> scrape(String query, boolean background) {
        if (!properties.isEnabled()) return Optional.empty();
        if (query == null || query.isBlank()) return Optional.empty();

        try {
            Map<String, Object> resp = client.get()
                    .uri(b -> b.path("/scrape")
                            .queryParam("query", query)
                            .queryParam("max_reviews", properties.getMaxReviews())
                            .queryParam("max_photos", properties.getMaxPhotos())
                            .queryParam("background", background)
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .block();

            if (resp == null || !Boolean.TRUE.equals(resp.get("found"))) {
                return Optional.empty();
            }
            return Optional.of(parse(resp));

        } catch (WebClientResponseException e) {
            // 503 = đang cooldown (throttle) → im lặng về Goong-only; 502/504 = miss/timeout
            if (e.getStatusCode().value() == 503) {
                log.debug("Scraper đang cooldown (throttle backoff) — bỏ qua '{}'", query);
            } else {
                log.debug("Scraper trả {} cho '{}' — về Goong-only", e.getStatusCode().value(), query);
            }
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Scraper không gọi được cho '{}': {} — về Goong-only", query, e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private ScrapedPlace parse(Map<String, Object> r) {
        return new ScrapedPlace(
                Boolean.TRUE.equals(r.get("found")),
                Boolean.TRUE.equals(r.get("degraded")),
                str(r.get("title")),
                num(r.get("rating")),
                intOrNull(r.get("reviewCount")),
                str(r.get("type")),
                str(r.get("phone")),
                str(r.get("website")),
                str(r.get("openState")),
                str(r.get("hoursText")),
                toJson(r.get("openingHours")),
                toJson(r.get("photos")),
                toJson(r.get("reviews")),
                num(r.get("latitude")),
                num(r.get("longitude")));
    }

    private String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isBlank() ? null : s;
    }

    private BigDecimal num(Object o) {
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }

    private Integer intOrNull(Object o) {
        if (o instanceof Number n) return n.intValue();
        return null;
    }

    /** Serialize list con (openingHours/photos/reviews) về JSON string để lưu vào PlaceCache. */
    private String toJson(Object o) {
        if (!(o instanceof List<?> list) || list.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.debug("Serialize scraper sub-list fail: {}", e.getMessage());
            return null;
        }
    }
}
