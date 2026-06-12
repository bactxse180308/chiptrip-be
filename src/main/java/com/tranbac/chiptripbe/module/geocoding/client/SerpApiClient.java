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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client gọi SerpApi (Google Maps engine) để enrich dữ liệu địa điểm:
 * rating, số review, giờ mở cửa, ảnh thumbnail, phone, website, place type.
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

    /**
     * Đầy đủ dữ liệu hiển thị Google Maps card cho 1 ứng viên (candidate)
     * trong local_results / place_results của SerpApi.
     */
    public record PlaceData(
            String dataId,
            String placeId,
            String title,
            String type,
            String address,
            BigDecimal rating,
            Integer reviewCount,
            String openState,
            /** Chuỗi rút gọn từ field "hours" (ví dụ "Open ⋅ Closes 10 PM"). */
            String hours,
            /** JSON string serialize từ field "operating_hours" (full lịch theo thứ). */
            String operatingHoursJson,
            Boolean openNow,
            String thumbnailUrl,
            String phone,
            String website,
            BigDecimal latitude,
            BigDecimal longitude
    ) {}

    /**
     * Trả về danh sách ứng viên từ SerpApi (engine=google_maps).
     * Caller (PlaceEnrichmentService) sẽ chọn ứng viên khớp nhất theo address/title/location.
     */
    @SuppressWarnings("unchecked")
    public List<PlaceData> searchPlaceCandidates(String query) {
        if (!properties.isEnabled()) return List.of();
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) return List.of();
        if (query == null || query.isBlank()) return List.of();

        try {
            log.info("SerpApi searchPlaceCandidates (engine=google_maps) for query='{}'", query);
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

            if (resp == null) return List.of();

            List<PlaceData> candidates = new ArrayList<>();

            Object localResultsObj = resp.get("local_results");
            if (localResultsObj instanceof List) {
                List<Map<String, Object>> places = (List<Map<String, Object>>) localResultsObj;
                for (Map<String, Object> place : places) {
                    parsePlaceData(place).ifPresent(candidates::add);
                }
            }

            if (candidates.isEmpty()) {
                Object placeResultsObj = resp.get("place_results");
                if (placeResultsObj instanceof Map) {
                    parsePlaceData((Map<String, Object>) placeResultsObj).ifPresent(candidates::add);
                }
            }

            return candidates;

        } catch (Exception e) {
            log.warn("SerpApi search failed for query='{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * Backward-compatible: trả về ứng viên đầu tiên. Caller mới nên dùng searchPlaceCandidates().
     */
    public Optional<PlaceData> searchPlace(String query) {
        List<PlaceData> candidates = searchPlaceCandidates(query);
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
    }

    /**
     * Dữ liệu khách sạn từ SerpApi Google Hotels.
     * pricePerNightVnd: lấy từ rate_per_night.extracted_lowest, đã sẵn VNĐ do currency=VND.
     */
    public record HotelData(
            String propertyToken,
            String name,
            Long pricePerNightVnd,
            BigDecimal rating,
            String thumbnailUrl,
            String bookingLink
    ) {}

    private static final DateTimeFormatter HOTEL_DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Tra cứu khách sạn theo tên + ngày checkin/checkout.
     * Engine google_hotels, currency=VND, gl=vn, hl=vi.
     * Trả candidate đầu tiên (best match theo SerpApi). Fail-soft → Optional.empty().
     */
    @SuppressWarnings("unchecked")
    public Optional<HotelData> searchHotel(String name, LocalDate checkIn, LocalDate checkOut, Integer adults) {
        if (!properties.isEnabled()) return Optional.empty();
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) return Optional.empty();
        if (name == null || name.isBlank()) return Optional.empty();
        if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn)) return Optional.empty();

        try {
            log.info("SerpApi searchHotel (engine=google_hotels) name='{}' {}..{}", name, checkIn, checkOut);
            Map<String, Object> resp = client.get()
                    .uri(b -> b.path("/search")
                            .queryParam("engine", "google_hotels")
                            .queryParam("q", name)
                            .queryParam("check_in_date", checkIn.format(HOTEL_DATE_FORMAT))
                            .queryParam("check_out_date", checkOut.format(HOTEL_DATE_FORMAT))
                            .queryParam("adults", adults != null ? adults.toString() : "2")
                            .queryParam("currency", "VND")
                            .queryParam("gl", "vn")
                            .queryParam("hl", "vi")
                            .queryParam("api_key", properties.getApiKey())
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .block();

            if (resp == null) return Optional.empty();
            List<Map<String, Object>> hotels = (List<Map<String, Object>>) resp.get("properties");
            if (hotels == null || hotels.isEmpty()) return Optional.empty();

            return parseHotelData(hotels.get(0));
        } catch (Exception e) {
            log.warn("SerpApi Google Hotels failed for name='{}': {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<HotelData> parseHotelData(Map<String, Object> p) {
        try {
            String propertyToken = stringOrNull(p.get("property_token"));
            String name = stringOrNull(p.get("name"));
            String link = stringOrNull(p.get("link"));
            BigDecimal rating = parseBigDecimal(p.get("overall_rating"));

            Long pricePerNight = null;
            
            // Ưu tiên tìm Trip.com trong danh sách prices
            Object pricesObj = p.get("prices");
            if (pricesObj instanceof List<?> prices) {
                for (Object item : prices) {
                    if (item instanceof Map<?, ?> priceItem) {
                        if ("Trip.com".equals(priceItem.get("source"))) {
                            String tLink = stringOrNull(priceItem.get("link"));
                            if (tLink != null) link = tLink; // Ghi đè link bằng link của Trip.com
                            
                            Object rateObj = priceItem.get("rate_per_night");
                            if (rateObj instanceof Map<?, ?> rate) {
                                Object extracted = rate.get("extracted_lowest");
                                if (extracted instanceof Number n) pricePerNight = n.longValue();
                            }
                            break;
                        }
                    }
                }
            }
            
            // Fallback nếu không tìm thấy Trip.com hoặc Trip.com không có giá
            if (pricePerNight == null) {
                Object rateObj = p.get("rate_per_night");
                if (rateObj instanceof Map<?, ?> rate) {
                    Object extracted = rate.get("extracted_lowest");
                    if (extracted instanceof Number n) pricePerNight = n.longValue();
                }
            }

            String thumbnail = null;
            Object imagesObj = p.get("images");
            if (imagesObj instanceof List<?> images && !images.isEmpty()
                    && images.get(0) instanceof Map<?, ?> firstImg) {
                Object thumb = firstImg.get("thumbnail");
                if (thumb instanceof String s) thumbnail = s;
                if (thumbnail == null && firstImg.get("original_image") instanceof String orig) thumbnail = orig;
            }

            if (name == null && link == null && pricePerNight == null && propertyToken == null) return Optional.empty();
            return Optional.of(new HotelData(propertyToken, name, pricePerNight, rating, thumbnail, link));
        } catch (Exception e) {
            log.debug("Failed to parse hotel data: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Deep-link đặt phòng + giá/đêm (VNĐ) từ Google Hotels Property Details. */
    public record HotelBooking(Long pricePerNightVnd, String bookingLink) {}

    /**
     * Google Hotels Property Details API (engine=google_hotels + property_token).
     * Trả deep-link đặt phòng theo từng nguồn (ưu tiên Trip.com) + giá/đêm.
     * Khác searchHotel(): search chỉ cho rate tổng hợp + link Google Travel generic;
     * property details mới có featured_prices/prices kèm link nhà cung cấp thật.
     * Fail-soft → Optional.empty().
     */
    @SuppressWarnings("unchecked")
    public Optional<HotelBooking> fetchHotelDetails(String propertyToken, LocalDate checkIn, LocalDate checkOut, Integer adults) {
        if (!properties.isEnabled() || properties.getApiKey() == null || properties.getApiKey().isBlank()) return Optional.empty();
        if (propertyToken == null || propertyToken.isBlank()) return Optional.empty();
        if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn)) return Optional.empty();

        try {
            log.info("SerpApi fetchHotelDetails (property details) propertyToken='{}' {}..{}", propertyToken, checkIn, checkOut);
            // property_token thay thế q — không truyền q kèm property_token
            Map<String, Object> resp = client.get()
                    .uri(b -> b.path("/search")
                            .queryParam("engine", "google_hotels")
                            .queryParam("property_token", propertyToken)
                            .queryParam("check_in_date", checkIn.format(HOTEL_DATE_FORMAT))
                            .queryParam("check_out_date", checkOut.format(HOTEL_DATE_FORMAT))
                            .queryParam("adults", adults != null ? adults.toString() : "2")
                            .queryParam("currency", "VND")
                            .queryParam("gl", "vn")
                            .queryParam("hl", "vi")
                            .queryParam("api_key", properties.getApiKey())
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .block();

            if (resp == null) return Optional.empty();

            // featured_prices ưu tiên hơn prices
            PriceLink best = pickBookingPrice(resp.get("featured_prices"));
            if (best == null) best = pickBookingPrice(resp.get("prices"));

            Long price = best != null ? best.price() : null;
            String link = best != null ? best.link() : null;

            // Fallback giá từ rate_per_night tổng nếu chưa có
            if (price == null && resp.get("rate_per_night") instanceof Map<?, ?> rate
                    && rate.get("extracted_lowest") instanceof Number n) {
                price = n.longValue();
            }

            if (price == null && link == null) return Optional.empty();
            return Optional.of(new HotelBooking(price, link));
        } catch (Exception e) {
            log.warn("SerpApi Google Hotels property details failed for token='{}': {}", propertyToken, e.getMessage());
            return Optional.empty();
        }
    }

    private record PriceLink(Long price, String link) {}

    /** Duyệt mảng price-source; ưu tiên Trip.com, nếu không có thì lấy nguồn đầu tiên có link. */
    private PriceLink pickBookingPrice(Object pricesObj) {
        if (!(pricesObj instanceof List<?> prices) || prices.isEmpty()) return null;
        PriceLink first = null;
        for (Object item : prices) {
            if (!(item instanceof Map<?, ?> priceItem)) continue;
            String source = stringOrNull(priceItem.get("source"));
            String link = stringOrNull(priceItem.get("link"));
            Long price = null;
            if (priceItem.get("rate_per_night") instanceof Map<?, ?> rate
                    && rate.get("extracted_lowest") instanceof Number n) {
                price = n.longValue();
            }
            if (link == null && price == null) continue;
            PriceLink pl = new PriceLink(price, link);
            if (first == null) first = pl;
            if (source != null && source.toLowerCase().contains("trip.com")) return pl;
        }
        return first;
    }

    /**
     * Lấy danh sách ảnh chi tiết của Hotel từ SerpApi (google_hotels_photos engine).
     * Yêu cầu propertyToken từ API google_hotels gốc.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, String>> fetchHotelPhotos(String propertyToken) {
        if (!properties.isEnabled() || properties.getApiKey() == null || properties.getApiKey().isBlank()) return List.of();
        if (propertyToken == null || propertyToken.isBlank()) return List.of();

        try {
            log.info("SerpApi fetching hotel photos for propertyToken='{}'", propertyToken);
            Map<String, Object> resp = client.get()
                    .uri(b -> b.path("/search")
                            .queryParam("engine", "google_hotels_photos")
                            .queryParam("q", "hotel")
                            .queryParam("property_token", propertyToken)
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
                        String photoUrl = (String) p.get("original_image");
                        String thumbnailUrl = (String) p.get("thumbnail");
                        if (photoUrl == null) photoUrl = thumbnailUrl;
                        if (photoUrl != null) {
                            return Map.of("url", photoUrl, "thumbnail", thumbnailUrl != null ? thumbnailUrl : photoUrl);
                        }
                        return null;
                    })
                    .filter(java.util.Objects::nonNull)
                    .limit(5)
                    .toList();

        } catch (Exception e) {
            log.warn("SerpApi hotel photos fetch failed for propertyToken='{}': {}", propertyToken, e.getMessage());
            return List.of();
        }
    }

    /**
     * Lấy danh sách review của Hotel từ SerpApi (google_hotels_reviews engine).
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchHotelReviews(String propertyToken) {
        if (!properties.isEnabled() || properties.getApiKey() == null || properties.getApiKey().isBlank()) return List.of();
        if (propertyToken == null || propertyToken.isBlank()) return List.of();

        try {
            log.info("SerpApi fetching hotel reviews for propertyToken='{}'", propertyToken);
            Map<String, Object> resp = client.get()
                    .uri(b -> b.path("/search")
                            .queryParam("engine", "google_hotels_reviews")
                            .queryParam("q", "hotel")
                            .queryParam("property_token", propertyToken)
                            .queryParam("api_key", properties.getApiKey())
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .block();

            if (resp == null) return List.of();

            List<Map<String, Object>> reviewsList = (List<Map<String, Object>>) resp.get("reviews");
            if (reviewsList == null || reviewsList.isEmpty()) return List.of();

            return reviewsList.stream()
                    .map(r -> {
                        String author = "Ẩn danh";
                        String text = (String) r.get("title");
                        if (text == null) text = (String) r.get("body");
                        
                        BigDecimal rating = null;
                        Object ratingObj = r.get("rating");
                        if (ratingObj instanceof Number) {
                            rating = BigDecimal.valueOf(((Number) ratingObj).doubleValue());
                        }
                        
                        if (text != null && !text.isBlank()) {
                            Map<String, Object> map = new java.util.HashMap<>();
                            map.put("author", author);
                            map.put("avatar", "");
                            map.put("rating", rating != null ? rating : BigDecimal.valueOf(5));
                            map.put("time", "Vừa xong");
                            map.put("text", text);
                            return map;
                        }
                        return null;
                    })
                    .filter(java.util.Objects::nonNull)
                    .limit(3)
                    .toList();

        } catch (Exception e) {
            log.warn("SerpApi hotel reviews fetch failed for propertyToken='{}': {}", propertyToken, e.getMessage());
            return List.of();
        }
    }

    /**
     * Lấy danh sách ảnh chi tiết từ SerpApi (google_maps_photos engine).
     * Giới hạn tối đa 5 ảnh để tiết kiệm.
     *
     * google_maps_photos engine yêu cầu `data_id` định dạng "0x...:0x..." (hex CID),
     * KHÔNG nhận place_id dạng "ChI...". Nếu id truyền vào là place_id thì skip để
     * tránh gọi API sai và lấy phải ảnh của địa điểm khác.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, String>> fetchPhotos(String id) {
        if (!properties.isEnabled()) return List.of();
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) return List.of();
        if (id == null || id.isBlank()) return List.of();
        if (id.startsWith("ChI")) {
            log.debug("SerpApi fetchPhotos: id='{}' là place_id, không hợp lệ cho google_maps_photos — skip", id);
            return List.of();
        }

        try {
            log.info("SerpApi fetching photos for dataId='{}'", id);
            Map<String, Object> resp = client.get()
                    .uri(b -> b.path("/search")
                            .queryParam("engine", "google_maps_photos")
                            .queryParam("data_id", id)
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
                    .limit(5)
                    .toList();

        } catch (Exception e) {
            log.warn("SerpApi photos fetch failed for dataId='{}': {}", id, e.getMessage());
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
                    .limit(3)
                    .toList();

        } catch (Exception e) {
            log.warn("SerpApi reviews fetch failed for dataId='{}': {}", dataId, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<PlaceData> parsePlaceData(Map<String, Object> p) {
        try {
            String title = stringOrNull(p.get("title"));
            String placeId = stringOrNull(p.get("place_id"));

            String dataId = stringOrNull(p.get("data_id"));
            if (dataId == null) dataId = stringOrNull(p.get("cid"));
            if (dataId == null) dataId = placeId;

            String type = stringOrNull(p.get("type"));
            String address = stringOrNull(p.get("address"));
            String openState = stringOrNull(p.get("open_state"));

            String hoursText = null;
            Object hoursObj = p.get("hours");
            if (hoursObj instanceof String s) {
                hoursText = s;
            }

            String operatingHoursJson = null;
            Object operatingHoursObj = p.get("operating_hours");
            if (operatingHoursObj != null) {
                try {
                    operatingHoursJson = objectMapper.writeValueAsString(operatingHoursObj);
                } catch (Exception ex) {
                    log.debug("Failed to serialize operating_hours: {}", ex.getMessage());
                }
            }

            // Legacy: hours object có thể chứa schedule + open_now (engine cũ)
            Boolean openNow = null;
            if (hoursObj instanceof Map) {
                Map<String, Object> hoursMap = (Map<String, Object>) hoursObj;
                Object openNowObj = hoursMap.get("open_now");
                if (openNowObj instanceof Boolean) openNow = (Boolean) openNowObj;
                if (operatingHoursJson == null) {
                    Object schedule = hoursMap.get("schedule");
                    if (schedule != null) {
                        try {
                            operatingHoursJson = objectMapper.writeValueAsString(schedule);
                        } catch (Exception ignored) {}
                    }
                }
            }

            BigDecimal rating = parseBigDecimal(p.get("rating"));
            Integer reviewCount = parseReviewCount(p);

            String thumbnail = stringOrNull(p.get("thumbnail"));
            String phone = stringOrNull(p.get("phone"));
            String website = stringOrNull(p.get("website"));

            BigDecimal lat = null, lng = null;
            Object gpsObj = p.get("gps_coordinates");
            if (gpsObj instanceof Map) {
                Map<String, Object> gps = (Map<String, Object>) gpsObj;
                lat = parseBigDecimal(gps.get("latitude"));
                lng = parseBigDecimal(gps.get("longitude"));
            }

            return Optional.of(new PlaceData(
                    dataId, placeId, title, type, address,
                    rating, reviewCount, openState, hoursText, operatingHoursJson,
                    openNow, thumbnail, phone, website, lat, lng));

        } catch (Exception e) {
            log.debug("Failed to parse SerpApi place data: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ─── Google Flights ─────────────────────────────────────────────────────

    public record FlightSegment(String airline, String airlineLogo, String flightNumber,
            String departureAirport, String departureTime,
            String arrivalAirport, String arrivalTime, Integer durationMinutes) {}

    public record FlightOption(Long priceVnd, Integer totalDurationMinutes, Integer stops,
            List<FlightSegment> segments, String departureToken, String bookingToken) {}

    public record FlightBookingOption(String source, Long priceVnd, String bookingLink) {}

    /**
     * Google Flights search (engine=google_flights).
     * type: 1=round-trip (cần returnDate), 2=one-way.
     * departureToken != null: lấy chặng kế tiếp (return) cho round-trip.
     * Ưu tiên best_flights, fallback other_flights. Fail-soft → List rỗng.
     */
    @SuppressWarnings("unchecked")
    public List<FlightOption> searchFlights(String departureId, String arrivalId,
            LocalDate outbound, LocalDate returnDate, Integer adults, String departureToken) {
        if (!properties.isEnabled() || apiKeyBlank()) return List.of();
        if (departureId == null || arrivalId == null || outbound == null) return List.of();
        boolean roundTrip = returnDate != null && returnDate.isAfter(outbound);
        try {
            log.info("SerpApi google_flights {}->{} {}{} token={}", departureId, arrivalId, outbound,
                    roundTrip ? (".." + returnDate) : " (one-way)", departureToken != null);
            Map<String, Object> resp = client.get()
                    .uri(b -> {
                        var u = b.path("/search")
                                .queryParam("engine", "google_flights")
                                .queryParam("departure_id", departureId)
                                .queryParam("arrival_id", arrivalId)
                                .queryParam("outbound_date", outbound.format(HOTEL_DATE_FORMAT))
                                .queryParam("type", roundTrip ? "1" : "2")
                                .queryParam("adults", adults != null ? adults.toString() : "1")
                                .queryParam("currency", "VND")
                                .queryParam("gl", "vn")
                                .queryParam("hl", "vi")
                                .queryParam("api_key", properties.getApiKey());
                        if (roundTrip) u.queryParam("return_date", returnDate.format(HOTEL_DATE_FORMAT));
                        if (departureToken != null && !departureToken.isBlank()) u.queryParam("departure_token", departureToken);
                        return u.build();
                    })
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .block();
            if (resp == null) return List.of();
            List<FlightOption> options = new ArrayList<>();
            parseFlightArray(resp.get("best_flights"), options);
            if (options.isEmpty()) parseFlightArray(resp.get("other_flights"), options);
            return options;
        } catch (Exception e) {
            log.warn("SerpApi google_flights failed {}->{}: {}", departureId, arrivalId, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private void parseFlightArray(Object arrObj, List<FlightOption> out) {
        if (!(arrObj instanceof List<?> arr)) return;
        for (Object o : arr) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Long price = m.get("price") instanceof Number n ? n.longValue() : null;
            Integer totalDuration = m.get("total_duration") instanceof Number n ? n.intValue() : null;
            String departureToken = stringOrNull(m.get("departure_token"));
            String bookingToken = stringOrNull(m.get("booking_token"));

            List<FlightSegment> segments = new ArrayList<>();
            if (m.get("flights") instanceof List<?> segs) {
                for (Object so : segs) {
                    if (so instanceof Map<?, ?> sm) segments.add(parseSegment((Map<String, Object>) sm));
                }
            }
            int stops = m.get("layovers") instanceof List<?> lay ? lay.size()
                    : Math.max(0, segments.size() - 1);

            out.add(new FlightOption(price, totalDuration, stops, segments, departureToken, bookingToken));
        }
    }

    private FlightSegment parseSegment(Map<String, Object> sm) {
        String airline = stringOrNull(sm.get("airline"));
        String airlineLogo = stringOrNull(sm.get("airline_logo"));
        String flightNumber = stringOrNull(sm.get("flight_number"));
        Integer duration = sm.get("duration") instanceof Number n ? n.intValue() : null;
        String depId = null, depTime = null, arrId = null, arrTime = null;
        if (sm.get("departure_airport") instanceof Map<?, ?> dep) {
            depId = stringOrNull(dep.get("id"));
            depTime = stringOrNull(dep.get("time"));
        }
        if (sm.get("arrival_airport") instanceof Map<?, ?> arr) {
            arrId = stringOrNull(arr.get("id"));
            arrTime = stringOrNull(arr.get("time"));
        }
        return new FlightSegment(airline, airlineLogo, flightNumber, depId, depTime, arrId, arrTime, duration);
    }

    /**
     * Booking Options cho 1 flight đã chọn (booking_token từ searchFlights).
     * Trả danh sách nguồn đặt vé (book_with) + giá + link. Fail-soft → List rỗng.
     */
    @SuppressWarnings("unchecked")
    public List<FlightBookingOption> fetchFlightBookingOptions(String departureId, String arrivalId,
            LocalDate outbound, LocalDate returnDate, Integer adults, String bookingToken) {
        if (!properties.isEnabled() || apiKeyBlank()) return List.of();
        if (bookingToken == null || bookingToken.isBlank()) return List.of();
        boolean roundTrip = returnDate != null && returnDate.isAfter(outbound);
        try {
            log.info("SerpApi google_flights booking options {}->{}", departureId, arrivalId);
            Map<String, Object> resp = client.get()
                    .uri(b -> {
                        var u = b.path("/search")
                                .queryParam("engine", "google_flights")
                                .queryParam("departure_id", departureId)
                                .queryParam("arrival_id", arrivalId)
                                .queryParam("outbound_date", outbound.format(HOTEL_DATE_FORMAT))
                                .queryParam("type", roundTrip ? "1" : "2")
                                .queryParam("adults", adults != null ? adults.toString() : "1")
                                .queryParam("currency", "VND")
                                .queryParam("gl", "vn")
                                .queryParam("hl", "vi")
                                .queryParam("booking_token", bookingToken)
                                .queryParam("api_key", properties.getApiKey());
                        if (roundTrip) u.queryParam("return_date", returnDate.format(HOTEL_DATE_FORMAT));
                        return u.build();
                    })
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .block();
            if (resp == null) return List.of();
            List<FlightBookingOption> result = new ArrayList<>();
            if (resp.get("booking_options") instanceof List<?> opts) {
                for (Object o : opts) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    Map<String, Object> sel = m.get("together") instanceof Map<?, ?> tg
                            ? (Map<String, Object>) tg : (Map<String, Object>) m;
                    String source = stringOrNull(sel.get("book_with"));
                    Long price = sel.get("price") instanceof Number n ? n.longValue() : null;
                    String link = sel.get("booking_request") instanceof Map<?, ?> br
                            ? stringOrNull(br.get("url")) : null;
                    if (source != null || link != null) result.add(new FlightBookingOption(source, price, link));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("SerpApi google_flights booking options failed {}->{}: {}", departureId, arrivalId, e.getMessage());
            return List.of();
        }
    }

    private boolean apiKeyBlank() {
        return properties.getApiKey() == null || properties.getApiKey().isBlank();
    }

    private static String stringOrNull(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.isBlank() ? null : s;
    }

    private static BigDecimal parseBigDecimal(Object o) {
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        if (o instanceof String s) {
            try {
                return new BigDecimal(s);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static Integer parseReviewCount(Map<String, Object> p) {
        Object reviewsCountObj = p.get("reviews_count");
        if (reviewsCountObj instanceof Number n) return n.intValue();
        if (reviewsCountObj instanceof String s) {
            try {
                return Integer.parseInt(s.replaceAll("[^0-9]", ""));
            } catch (Exception ignored) {}
        }

        Object reviewObj = p.get("reviews");
        if (reviewObj instanceof Number n) return n.intValue();
        if (reviewObj instanceof String s) {
            try {
                return Integer.parseInt(s.replaceAll("[^0-9]", ""));
            } catch (Exception ignored) {}
        }

        Object reviewsOriginalObj = p.get("reviews_original");
        if (reviewsOriginalObj instanceof String orig) {
            try {
                if (orig.contains("K") || orig.contains("k")) {
                    String numStr = orig.replaceAll("[^0-9.]", "");
                    double num = Double.parseDouble(numStr);
                    return (int) (num * 1000);
                }
                return Integer.parseInt(orig.replaceAll("[^0-9]", ""));
            } catch (Exception ignored) {}
        }
        return null;
    }
}
