package com.tranbac.chiptripbe.module.geocoding.client;

import com.tranbac.chiptripbe.common.config.GoongProperties;
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
 * Client gọi Goong API: forward geocode và place autocomplete.
 * API key đọc từ app.goong.api-key — không hardcode.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoongClient {

    private final WebClient.Builder webClientBuilder;
    private final GoongProperties properties;

    private WebClient client;

    @PostConstruct
    void init() {
        client = webClientBuilder.baseUrl(properties.getBaseUrl()).build();
    }

    public record GeocodeResult(String placeId, String formattedAddress, BigDecimal lat, BigDecimal lng) {}

    public record AutocompleteResult(String placeId, String description, String mainText, String secondaryText) {}

    /**
     * Đổi địa chỉ/tên địa điểm thành tọa độ.
     * Trả Optional.empty() nếu không tìm thấy hoặc API fail.
     */
    @SuppressWarnings("unchecked")
    public Optional<GeocodeResult> forwardGeocode(String query) {
        if (query == null || query.isBlank()) return Optional.empty();
        try {
            Map<String, Object> resp = client.get()
                    .uri(b -> b.path("/geocode")
                            .queryParam("address", query)
                            .queryParam("api_key", properties.getApiKey())
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .block();

            if (resp == null) return Optional.empty();

            String status = (String) resp.get("status");
            if (!"OK".equals(status)) {
                log.debug("Goong geocode status={} for query='{}'", status, query);
                return Optional.empty();
            }

            List<Map<String, Object>> results = (List<Map<String, Object>>) resp.get("results");
            if (results == null || results.isEmpty()) return Optional.empty();

            return parseGeocodeResult(results.get(0));

        } catch (Exception e) {
            log.warn("Goong forward geocode failed for query='{}': {}", query, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Gợi ý địa điểm khi user nhập (dùng cho autocomplete frontend).
     */
    @SuppressWarnings("unchecked")
    public List<AutocompleteResult> autocomplete(String input) {
        if (input == null || input.isBlank()) return List.of();
        try {
            Map<String, Object> resp = client.get()
                    .uri(b -> b.path("/Place/AutoComplete")
                            .queryParam("input", input)
                            .queryParam("api_key", properties.getApiKey())
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .block();

            if (resp == null) return List.of();

            List<Map<String, Object>> predictions = (List<Map<String, Object>>) resp.getOrDefault("predictions", List.of());
            return predictions.stream().limit(5).map(p -> {
                Map<String, Object> sf = (Map<String, Object>) p.getOrDefault("structured_formatting", Map.of());
                return new AutocompleteResult(
                        (String) p.get("place_id"),
                        (String) p.getOrDefault("description", ""),
                        (String) sf.getOrDefault("main_text", ""),
                        (String) sf.getOrDefault("secondary_text", "")
                );
            }).toList();

        } catch (Exception e) {
            log.warn("Goong autocomplete failed for input='{}': {}", input, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<GeocodeResult> parseGeocodeResult(Map<String, Object> result) {
        String placeId = (String) result.get("place_id");
        String formattedAddress = (String) result.get("formatted_address");

        BigDecimal lat = null, lng = null;
        Map<String, Object> geometry = (Map<String, Object>) result.get("geometry");
        if (geometry != null) {
            Map<String, Object> location = (Map<String, Object>) geometry.get("location");
            if (location != null) {
                Object latObj = location.get("lat"), lngObj = location.get("lng");
                if (latObj instanceof Number) lat = BigDecimal.valueOf(((Number) latObj).doubleValue());
                if (lngObj instanceof Number) lng = BigDecimal.valueOf(((Number) lngObj).doubleValue());
            }
        }

        if (lat == null || lng == null) return Optional.empty();

        log.debug("Goong geocoded → lat={} lng={} placeId={}", lat, lng, placeId);
        return Optional.of(new GeocodeResult(placeId, formattedAddress, lat, lng));
    }
}