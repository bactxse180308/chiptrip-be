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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client gọi Goong API v2.
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

    /**
     * Kết quả forward geocode.
     * provinceName / communeName: lấy từ Goong V2 compound (has_vnid). Null nếu V2 không trả compound
     * (V1 endpoint, response thiếu, hoặc plan chưa bật has_vnid).
     */
    public record GeocodeResult(
            String placeId,
            String formattedAddress,
            BigDecimal lat,
            BigDecimal lng,
            String provinceName,
            String communeName
    ) {}

    public record AutocompleteResult(String placeId, String description, String mainText, String secondaryText) {}

    public record DirectionResult(int distanceMeters, int durationSeconds, String overviewPolyline) {}

    public record TravelSegment(int distanceMeters, int durationSeconds) {}

    /** Cache đơn giản cho Direction — route giữa 2 tọa độ không đổi nên cache vô thời hạn. */
    private static final int DIRECTION_CACHE_MAX = 5_000;
    private final ConcurrentHashMap<String, DirectionResult> directionCache = new ConcurrentHashMap<>();

    /**
     * Đổi địa chỉ/tên địa điểm thành tọa độ qua V2 (/v2/geocode + has_vnid=true).
     * Trả Optional.empty() nếu không tìm thấy hoặc API fail.
     */
    @SuppressWarnings("unchecked")
    public Optional<GeocodeResult> forwardGeocode(String query) {
        if (query == null || query.isBlank()) return Optional.empty();
        try {
            Map<String, Object> resp = client.get()
                    .uri(b -> b.path("/v2/geocode")
                            .queryParam("address", query)
                            .queryParam("has_vnid", "true")
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
     * Lấy chi tiết địa điểm từ Goong place_id (sau khi user chọn 1 autocomplete prediction).
     * Endpoint: GET /Place/Detail?place_id=...&api_key=...
     * Response wrap "result" (số ít, khác geocode dùng "results") chứa formatted_address +
     * geometry.location + compound — tái dùng parseGeocodeResult cho province/commune.
     */
    @SuppressWarnings("unchecked")
    public Optional<GeocodeResult> placeDetail(String placeId) {
        if (placeId == null || placeId.isBlank()) return Optional.empty();
        try {
            Map<String, Object> resp = client.get()
                    .uri(b -> b.path("/v2/place/detail")
                            .queryParam("place_id", placeId)
                            .queryParam("api_key", properties.getApiKey())
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .block();

            if (resp == null) return Optional.empty();
            Object resultObj = resp.get("result");
            if (!(resultObj instanceof Map<?, ?> result)) {
                log.debug("Goong Place/Detail không có result cho placeId='{}'", placeId);
                return Optional.empty();
            }
            return parseGeocodeResult((Map<String, Object>) result);
        } catch (Exception e) {
            log.warn("Goong place detail failed for placeId='{}': {}", placeId, e.getMessage());
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
                    .uri(b -> b.path("/v2/place/autocomplete")
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

    /**
     * Gọi /Direction lấy khoảng cách + thời gian + polyline 1 chặng.
     * Cache theo (oLat,oLng,dLat,dLng,vehicle) — route cố định không đổi.
     */
    @SuppressWarnings("unchecked")
    public Optional<DirectionResult> direction(double oLat, double oLng,
                                                double dLat, double dLng, String vehicle) {
        String v = normalizeVehicle(vehicle);
        String key = oLat + "," + oLng + "|" + dLat + "," + dLng + "|" + v;
        DirectionResult cached = directionCache.get(key);
        if (cached != null) return Optional.of(cached);

        try {
            Map<String, Object> resp = client.get()
                    .uri(b -> b.path("/v2/direction")
                            .queryParam("origin", oLat + "," + oLng)
                            .queryParam("destination", dLat + "," + dLng)
                            .queryParam("vehicle", v)
                            .queryParam("api_key", properties.getApiKey())
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .block();

            if (resp == null) return Optional.empty();
            List<Map<String, Object>> routes = (List<Map<String, Object>>) resp.get("routes");
            if (routes == null || routes.isEmpty()) return Optional.empty();

            Map<String, Object> route = routes.get(0);
            List<Map<String, Object>> legs = (List<Map<String, Object>>) route.get("legs");
            if (legs == null || legs.isEmpty()) return Optional.empty();

            Map<String, Object> leg = legs.get(0);
            int distance = intValue(((Map<String, Object>) leg.get("distance")), "value");
            int duration = intValue(((Map<String, Object>) leg.get("duration")), "value");
            String polyline = "";
            Map<String, Object> overview = (Map<String, Object>) route.get("overview_polyline");
            if (overview != null && overview.get("points") instanceof String s) polyline = s;

            DirectionResult result = new DirectionResult(distance, duration, polyline);
            if (directionCache.size() < DIRECTION_CACHE_MAX) directionCache.put(key, result);
            return Optional.of(result);

        } catch (Exception e) {
            log.warn("Goong direction failed origin=({},{}) dest=({},{}) vehicle={}: {}",
                    oLat, oLng, dLat, dLng, v, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Gọi /DistanceMatrix với pattern consecutive: origins[i] → destinations[i] (diagonal).
     * Trả List có cùng size với origins; phần tử null nếu Goong báo không OK cho cặp đó.
     */
    @SuppressWarnings("unchecked")
    public List<TravelSegment> distanceMatrix(List<double[]> origins, List<double[]> destinations, String vehicle) {
        if (origins == null || destinations == null || origins.isEmpty() || origins.size() != destinations.size()) {
            return List.of();
        }
        String v = normalizeVehicle(vehicle);
        String originsStr = origins.stream().map(p -> p[0] + "," + p[1]).reduce((a, b) -> a + "|" + b).orElse("");
        String destsStr = destinations.stream().map(p -> p[0] + "," + p[1]).reduce((a, b) -> a + "|" + b).orElse("");

        try {
            Map<String, Object> resp = client.get()
                    .uri(b -> b.path("/v2/distancematrix")
                            .queryParam("origins", originsStr)
                            .queryParam("destinations", destsStr)
                            .queryParam("vehicle", v)
                            .queryParam("api_key", properties.getApiKey())
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .block();

            if (resp == null) return nullList(origins.size());
            List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.get("rows");
            if (rows == null) return nullList(origins.size());

            List<TravelSegment> result = new java.util.ArrayList<>(origins.size());
            for (int i = 0; i < origins.size(); i++) {
                if (i >= rows.size()) { result.add(null); continue; }
                List<Map<String, Object>> elements = (List<Map<String, Object>>) rows.get(i).get("elements");
                if (elements == null || i >= elements.size()) { result.add(null); continue; }
                Map<String, Object> el = elements.get(i);
                String status = (String) el.get("status");
                if (!"OK".equals(status)) { result.add(null); continue; }
                int dist = intValue((Map<String, Object>) el.get("distance"), "value");
                int dur = intValue((Map<String, Object>) el.get("duration"), "value");
                result.add(new TravelSegment(dist, dur));
            }
            return result;

        } catch (Exception e) {
            log.warn("Goong distance matrix failed: {}", e.getMessage());
            return nullList(origins.size());
        }
    }

    /**
     * Reverse geocode (lat/lng → tên province). Best-effort, trả Optional.empty() khi fail.
     */
    @SuppressWarnings("unchecked")
    public Optional<String> reverseGeocodeProvince(double lat, double lng) {
        try {
            Map<String, Object> resp = client.get()
                    .uri(b -> b.path("/v2/geocode")
                            .queryParam("latlng", lat + "," + lng)
                            .queryParam("api_key", properties.getApiKey())
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .block();

            if (resp == null) return Optional.empty();
            List<Map<String, Object>> results = (List<Map<String, Object>>) resp.get("results");
            if (results == null || results.isEmpty()) return Optional.empty();

            Map<String, Object> first = results.get(0);
            Object compoundObj = first.get("compound");
            if (compoundObj instanceof Map<?, ?> compound && compound.get("province") instanceof String p && !p.isBlank()) {
                return Optional.of(p);
            }
            // Fallback: tách phần cuối formatted_address
            Object addrObj = first.get("formatted_address");
            if (addrObj instanceof String s) {
                int idx = s.lastIndexOf(',');
                return Optional.of(idx < 0 ? s.trim() : s.substring(idx + 1).trim());
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Goong reverse geocode failed for ({},{}): {}", lat, lng, e.getMessage());
            return Optional.empty();
        }
    }

    private static String normalizeVehicle(String v) {
        if (v == null) return "car";
        return switch (v.toLowerCase()) {
            case "bike" -> "bike";
            case "taxi", "hd", "car" -> "car";
            default -> "car";
        };
    }

    private static int intValue(Map<String, Object> m, String key) {
        if (m == null) return 0;
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static List<TravelSegment> nullList(int size) {
        List<TravelSegment> list = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) list.add(null);
        return list;
    }

    @SuppressWarnings("unchecked")
    Optional<GeocodeResult> parseGeocodeResult(Map<String, Object> result) {
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

        // V2 has_vnid: compound = { "province": "...", "commune": "..." }. Fail-soft nếu thiếu.
        String provinceName = null, communeName = null;
        Object compoundObj = result.get("compound");
        if (compoundObj instanceof Map<?, ?> compound) {
            Object p = compound.get("province");
            if (p instanceof String s && !s.isBlank()) provinceName = s;
            Object c = compound.get("commune");
            if (c instanceof String s && !s.isBlank()) communeName = s;
        }

        log.debug("Goong geocoded → lat={} lng={} placeId={} province={} commune={}",
                lat, lng, placeId, provinceName, communeName);
        return Optional.of(new GeocodeResult(placeId, formattedAddress, lat, lng, provinceName, communeName));
    }
}