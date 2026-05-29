package com.tranbac.chiptripbe.module.external.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.module.external.dto.PlaceSearchResponse;
import com.tranbac.chiptripbe.module.external.dto.WeatherResponse;
import com.tranbac.chiptripbe.module.external.service.ExternalApiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@Tag(name = "External", description = "Dịch vụ bên ngoài (Maps, Weather)")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ExternalController {

    private final ExternalApiService externalApiService;

    @Operation(summary = "Autocomplete tên thành phố (Google Maps Places)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/places/search")
    public ResponseEntity<ApiResponse<PlaceSearchResponse>> searchPlaces(@RequestParam String q) {
        return ResponseEntity.ok(ApiResponse.ok(externalApiService.searchPlaces(q)));
    }

    @Operation(summary = "Dự báo thời tiết ngày trong chuyến đi (OpenWeather)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/weather")
    public ResponseEntity<ApiResponse<WeatherResponse>> getWeather(
            @RequestParam String city,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.ok(externalApiService.getWeather(city, from, to)));
    }
}
