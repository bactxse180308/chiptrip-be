package com.tranbac.chiptripbe.module.external.service;

import com.tranbac.chiptripbe.module.external.dto.response.PlaceSearchResponse;
import com.tranbac.chiptripbe.module.external.dto.response.WeatherResponse;
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

@Service
public class ExternalApiService {

    private final WebClient webClient = WebClient.builder().build();

    @Value("${app.external.google-places-api-key:}")
    private String googlePlacesKey;

    @Value("${app.external.openweather-api-key:}")
    private String openWeatherKey;

    @SuppressWarnings("unchecked")
    public PlaceSearchResponse searchPlaces(String query) {
        if (googlePlacesKey == null || googlePlacesKey.isBlank()) {
            return buildFallbackPlaces(query);
        }
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://maps.googleapis.com/maps/api/place/autocomplete/json?input="
                    + encodedQuery + "&types=(cities)&language=vi&key=" + googlePlacesKey;

            Map<String, Object> response = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(5))
                    .onErrorResume(e -> Mono.just(Map.of("predictions", List.of())))
                    .block();

            List<Map<String, Object>> predictions = (List<Map<String, Object>>) response.getOrDefault("predictions", List.of());
            List<PlaceSearchResponse.Place> places = new ArrayList<>();
            for (Map<String, Object> p : predictions) {
                if (places.size() >= 5) break;
                Map<String, Object> structured = (Map<String, Object>) p.getOrDefault("structured_formatting", Map.of());
                places.add(PlaceSearchResponse.Place.builder()
                        .description((String) p.getOrDefault("description", ""))
                        .mainText((String) structured.getOrDefault("main_text", ""))
                        .secondaryText((String) structured.getOrDefault("secondary_text", ""))
                        .build());
            }
            return PlaceSearchResponse.builder().predictions(places).build();
        } catch (Exception e) {
            return buildFallbackPlaces(query);
        }
    }

    public WeatherResponse getWeather(String city, LocalDate from, LocalDate to) {
        if (openWeatherKey == null || openWeatherKey.isBlank()) {
            return buildFallbackWeather(city, from, to);
        }
        try {
            String encodedCity = URLEncoder.encode(city + ",Vietnam", StandardCharsets.UTF_8);
            String geoUrl = "https://api.openweathermap.org/geo/1.0/direct?q=" + encodedCity
                    + "&limit=1&appid=" + openWeatherKey;

            List<Map<String, Object>> geoResult = webClient.get()
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

            Map<String, Object> forecastResult = webClient.get()
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
            return buildFallbackWeather(city, from, to);
        }
    }

    private PlaceSearchResponse buildFallbackPlaces(String query) {
        String[][] cityHints = {
                {"Ha Noi", "Thu do Viet Nam"},
                {"Da Nang", "Thanh pho mien Trung"},
                {"Ho Chi Minh", "Thanh pho Ho Chi Minh"},
                {"Hoi An", "Pho co Hoi An, Quang Nam"},
                {"Nha Trang", "Thanh pho bien Khanh Hoa"}
        };
        List<PlaceSearchResponse.Place> places = new ArrayList<>();
        for (String[] c : cityHints) {
            if (c[0].toLowerCase().contains(query.toLowerCase())) {
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
