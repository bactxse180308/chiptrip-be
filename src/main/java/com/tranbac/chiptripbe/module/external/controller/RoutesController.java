package com.tranbac.chiptripbe.module.external.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.module.external.dto.request.DistanceMatrixRequest;
import com.tranbac.chiptripbe.module.external.dto.response.DirectionResponse;
import com.tranbac.chiptripbe.module.external.dto.response.DistanceMatrixResponse;
import com.tranbac.chiptripbe.module.geocoding.client.GoongClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Proxy các route API của Goong (Direction, Distance Matrix, Reverse Geocode) — thu hồi
 * VITE_GOONG_API_KEY khỏi FE bundle, đồng thời cache Direction để giảm Direction count.
 */
@Tag(name = "Routes", description = "Lộ trình + khoảng cách (proxy Goong)")
@RestController
@RequestMapping("/api/v1/routes")
@RequiredArgsConstructor
public class RoutesController {

    private final GoongClient goongClient;

    @Operation(summary = "Lấy lộ trình A→B (Goong Direction)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/direction")
    public ResponseEntity<ApiResponse<DirectionResponse>> direction(
            @RequestParam double oLat,
            @RequestParam double oLng,
            @RequestParam double dLat,
            @RequestParam double dLng,
            @RequestParam(required = false, defaultValue = "car") String vehicle) {

        return goongClient.direction(oLat, oLng, dLat, dLng, vehicle)
                .map(r -> ResponseEntity.ok(ApiResponse.ok(DirectionResponse.builder()
                        .distanceMeters(r.distanceMeters())
                        .durationSeconds(r.durationSeconds())
                        .overviewPolyline(r.overviewPolyline())
                        .build())))
                .orElseGet(() -> ResponseEntity.ok(ApiResponse.ok(null)));
    }

    @Operation(summary = "Tính thời gian/khoảng cách giữa các cặp tọa độ liên tiếp (Goong DistanceMatrix)")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/distance-matrix")
    public ResponseEntity<ApiResponse<DistanceMatrixResponse>> distanceMatrix(
            @Valid @RequestBody DistanceMatrixRequest request) {

        List<DistanceMatrixRequest.Point> points = request.getPoints();
        if (points == null || points.size() < 2) {
            return ResponseEntity.ok(ApiResponse.ok(DistanceMatrixResponse.builder().segments(List.of()).build()));
        }

        List<double[]> origins = points.subList(0, points.size() - 1).stream()
                .map(p -> new double[]{p.getLat(), p.getLng()}).toList();
        List<double[]> dests = points.subList(1, points.size()).stream()
                .map(p -> new double[]{p.getLat(), p.getLng()}).toList();

        List<GoongClient.TravelSegment> raw = goongClient.distanceMatrix(origins, dests, request.getVehicle());
        List<DistanceMatrixResponse.Segment> segments = raw.stream()
                .map(s -> s == null ? null : DistanceMatrixResponse.Segment.builder()
                        .distanceMeters(s.distanceMeters())
                        .durationSeconds(s.durationSeconds())
                        .build())
                .toList();

        return ResponseEntity.ok(ApiResponse.ok(DistanceMatrixResponse.builder().segments(segments).build()));
    }

    @Operation(summary = "Reverse geocode: lat/lng → tên tỉnh/thành (Goong)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/reverse-geocode")
    public ResponseEntity<ApiResponse<String>> reverseGeocode(
            @RequestParam double lat,
            @RequestParam double lng) {
        return ResponseEntity.ok(ApiResponse.ok(goongClient.reverseGeocodeProvince(lat, lng).orElse(null)));
    }
}
