package com.tranbac.chiptripbe.module.stats.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.module.stats.dto.response.AiCostByProviderMonthResponse;
import com.tranbac.chiptripbe.module.stats.dto.response.DailyCountResponse;
import com.tranbac.chiptripbe.module.stats.dto.response.DashboardResponse;
import com.tranbac.chiptripbe.module.stats.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Admin — Stats", description = "Thống kê hệ thống")
@RestController
@RequestMapping("/api/v1/admin/stats")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminStatsController {

    private final StatsService statsService;

    @Operation(summary = "Tổng quan: tổng user, tổng trip, chi phí AI tháng này")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<DashboardResponse>> getDashboard() {
        return ResponseEntity.ok(ApiResponse.ok(statsService.getDashboard()));
    }

    @Operation(summary = "Số user đăng ký theo ngày (from/to tùy chọn, mặc định lấy tất cả)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/users")
    public ResponseEntity<ApiResponse<List<DailyCountResponse>>> getUserStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.ok(statsService.getUserRegistrationsByDay(from, to)));
    }

    @Operation(summary = "Số trip được tạo theo ngày (from/to tùy chọn, mặc định lấy tất cả)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/trips")
    public ResponseEntity<ApiResponse<List<DailyCountResponse>>> getTripStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.ok(statsService.getTripCreationsByDay(from, to)));
    }

    @Operation(summary = "Chi phí AI theo provider và tháng (from/to tùy chọn, mặc định lấy tất cả)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/ai-cost")
    public ResponseEntity<ApiResponse<List<AiCostByProviderMonthResponse>>> getAiCostStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.ok(statsService.getAiCostByProviderMonth(from, to)));
    }
}
