package com.tranbac.chiptripbe.module.external.service;

import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.external.dto.response.PlaceLookupResponse;
import com.tranbac.chiptripbe.module.external.dto.response.PlaceSearchResponse;
import com.tranbac.chiptripbe.module.external.dto.response.WeatherResponse;
import com.tranbac.chiptripbe.module.geocoding.client.GoongClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import reactor.core.publisher.Mono;

@Slf4j
@Service
public class ExternalApiService {

    private final GoongClient goongClient;
    private final WebClient weatherClient;

    @Value("${app.external.openweather-api-key:}")
    private String openWeatherKey;

    public ExternalApiService(GoongClient goongClient, WebClient.Builder webClientBuilder) {
        this.goongClient = goongClient;
        this.weatherClient = webClientBuilder.build();
    }

    public PlaceSearchResponse searchPlaces(String query) {
        List<GoongClient.AutocompleteResult> results = goongClient.autocomplete(query);
        if (results.isEmpty()) return buildFallbackPlaces(query);

        List<PlaceSearchResponse.Place> places = results.stream()
                .map(r -> PlaceSearchResponse.Place.builder()
                        .placeId(r.placeId())
                        .description(r.description())
                        .mainText(r.mainText())
                        .secondaryText(r.secondaryText())
                        .build())
                .toList();
        return PlaceSearchResponse.builder().predictions(places).build();
    }

    /**
     * Tra cứu lat/lng + admin info từ Goong placeId (sau khi user chọn 1 autocomplete prediction).
     * Throw 404 nếu placeId không hợp lệ hoặc Goong không trả về data.
     */
    public PlaceLookupResponse lookupPlace(String placeId) {
        return goongClient.placeDetail(placeId)
                .map(g -> PlaceLookupResponse.builder()
                        .placeId(g.placeId())
                        .formattedAddress(g.formattedAddress())
                        .latitude(g.lat())
                        .longitude(g.lng())
                        .provinceName(g.provinceName())
                        .communeName(g.communeName())
                        .build())
                .orElseThrow(() -> AppException.notFound("Không tìm thấy địa điểm với placeId này"));
    }

    @SuppressWarnings("unchecked")
    public WeatherResponse getWeather(String city, LocalDate from, LocalDate to) {
        if (openWeatherKey == null || openWeatherKey.isBlank()) {
            return buildFallbackWeather(city, from, to);
        }
        try {
            String encodedCity = URLEncoder.encode(city + ",Vietnam", StandardCharsets.UTF_8);
            String geoUrl = "https://api.openweathermap.org/geo/1.0/direct?q=" + encodedCity
                    + "&limit=1&appid=" + openWeatherKey;

            List<Map<String, Object>> geoResult = weatherClient.get()
                    .uri(geoUrl)
                    .retrieve()
                    .bodyToMono(List.class)
                    .timeout(Duration.ofSeconds(5))
                    .onErrorResume(e -> Mono.just(List.of()))
                    .block();

            if (geoResult == null || geoResult.isEmpty()) {
                return buildFallbackWeather(city, from, to);
            }

            Map<String, Object> geo = geoResult.get(0);
            double lat = ((Number) geo.get("lat")).doubleValue();
            double lon = ((Number) geo.get("lon")).doubleValue();

            String forecastUrl = "https://api.openweathermap.org/data/2.5/forecast?lat=" + lat
                    + "&lon=" + lon + "&appid=" + openWeatherKey + "&units=metric";

            Map<String, Object> forecastResult = weatherClient.get()
                    .uri(forecastUrl)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(5))
                    .onErrorResume(e -> Mono.just(Map.of("list", List.of())))
                    .block();

            List<Map<String, Object>> list = (List<Map<String, Object>>) forecastResult.getOrDefault("list", List.of());

            List<WeatherResponse.DayForecast> forecasts = new ArrayList<>();
            for (Map<String, Object> item : list) {
                if (forecasts.size() >= 40) break;

                Map<String, Object> main = (Map<String, Object>) item.get("main");
                List<Map<String, Object>> weather = (List<Map<String, Object>>) item.getOrDefault("weather", List.of());
                Map<String, Object> wind = (Map<String, Object>) item.getOrDefault("wind", Map.of());
                String dtTxt = (String) item.get("dt_txt");
                String dateStr = dtTxt != null && dtTxt.contains(" ") ? dtTxt.split(" ")[0] : "";
                LocalDate date = LocalDate.parse(dateStr);

                String condition = weather.isEmpty() ? "unknown" : (String) weather.get(0).get("main");
                String icon = weather.isEmpty() ? "" : (String) weather.get(0).get("icon");
                String description = weather.isEmpty() ? "" : (String) weather.get(0).get("description");

                forecasts.add(WeatherResponse.DayForecast.builder()
                        .date(date)
                        .condition(condition)
                        .icon(icon)
                        .tempMin(((Number) main.get("temp_min")).doubleValue())
                        .tempMax(((Number) main.get("temp_max")).doubleValue())
                        .humidity(((Number) main.get("humidity")).doubleValue())
                        .windSpeed(((Number) wind.getOrDefault("speed", 0)).doubleValue())
                        .description(description)
                        .build());
            }
            return WeatherResponse.builder().city(city).forecasts(forecasts).build();
        } catch (Exception e) {
            log.warn("Weather API failed for city='{}': {}", city, e.getMessage());
            return buildFallbackWeather(city, from, to);
        }
    }

    private PlaceSearchResponse buildFallbackPlaces(String query) {
        String[][] cityHints = {
                {"Hà Nội", "Thủ đô Việt Nam"},
                {"Đà Nẵng", "Thành phố miền Trung"},
                {"Hồ Chí Minh", "Thành phố Hồ Chí Minh"},
                {"Hội An", "Phố cổ Hội An, Quảng Nam"},
                {"Nha Trang", "Thành phố biển Khánh Hòa"},
                {"Đà Lạt", "Thành phố ngàn hoa"},
                {"Phú Quốc", "Đảo ngọc Phú Quốc"},
        };
        List<PlaceSearchResponse.Place> places = new ArrayList<>();
        String q = query.toLowerCase();
        for (String[] c : cityHints) {
            if (c[0].toLowerCase().contains(q) || c[1].toLowerCase().contains(q)) {
                places.add(PlaceSearchResponse.Place.builder()
                        .description(c[0] + " - " + c[1])
                        .mainText(c[0])
                        .secondaryText(c[1])
                        .build());
            }
        }
        return PlaceSearchResponse.builder().predictions(places).build();
    }

    private WeatherResponse buildFallbackWeather(String city, LocalDate from, LocalDate to) {
        String[] conditions = {"Clear", "Clouds", "Rain", "Sunny"};
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        List<WeatherResponse.DayForecast> forecasts = new ArrayList<>();
        for (int i = 0; i < Math.min(days, 5); i++) {
            LocalDate d = from.plusDays(i);
            int idx = i % conditions.length;
            forecasts.add(WeatherResponse.DayForecast.builder()
                    .date(d)
                    .condition(conditions[idx])
                    .icon("01d")
                    .tempMin(22.0 + i)
                    .tempMax(30.0 + i)
                    .humidity(65.0)
                    .windSpeed(3.5)
                    .description("Weather " + conditions[idx].toLowerCase())
                    .build());
        }
        return WeatherResponse.builder().city(city).forecasts(forecasts).build();
    }
}
