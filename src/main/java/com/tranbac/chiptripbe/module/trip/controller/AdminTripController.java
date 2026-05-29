package com.tranbac.chiptripbe.module.trip.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.common.response.PageMeta;
import com.tranbac.chiptripbe.module.trip.dto.response.TripDetailResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripSummaryResponse;
import com.tranbac.chiptripbe.module.trip.service.AdminTripService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Admin — Trips", description = "Quản trị chuyến đi")
@RestController
@RequestMapping("/api/v1/admin/trips")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminTripController {

    private final AdminTripService adminTripService;

    @Operation(summary = "Danh sách tất cả chuyến đi (lọc theo userId, from, to)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ResponseEntity<ApiResponse<List<TripSummaryResponse>>> getAllTrips(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<TripSummaryResponse> result = adminTripService.getAllTrips(
                userId, from, to,
                PageRequest.of(page, size, Sort.by("createdAt").descending()));

        return ResponseEntity.ok(ApiResponse.ok(result.getContent(), PageMeta.of(result)));
    }

    @Operation(summary = "Chi tiết chuyến đi (bao gồm ngày, hoạt động, checklist)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TripDetailResponse>> getTripDetail(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(adminTripService.getTripDetail(id)));
    }

    @Operation(summary = "Xoá chuyến đi vi phạm")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteTrip(@PathVariable Long id) {
        adminTripService.deleteTrip(id);
        return ResponseEntity.ok(ApiResponse.noContent());
    }
}
