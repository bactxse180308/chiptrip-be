package com.tranbac.chiptripbe.module.stats.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.module.stats.dto.response.AiCostByProviderMonthResponse;
import com.tranbac.chiptripbe.module.stats.dto.response.DailyCountResponse;
import com.tranbac.chiptripbe.module.stats.dto.response.DailyRevenueResponse;
import com.tranbac.chiptripbe.module.stats.dto.response.DashboardResponse;
import com.tranbac.chiptripbe.module.stats.dto.response.EventCountResponse;
import com.tranbac.chiptripbe.module.stats.service.AnalyticsService;
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
    private final AnalyticsService analyticsService;

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

    @Operation(summary = "Doanh thu + số đơn PAID theo ngày (from/to tùy chọn, mặc định lấy tất cả)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/revenue")
    public ResponseEntity<ApiResponse<List<DailyRevenueResponse>>> getRevenueStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.ok(statsService.getRevenueByDay(from, to)));
    }

    @Operation(summary = "Số lượt gọi AI theo ngày (from/to tùy chọn, mặc định lấy tất cả)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/ai-calls")
    public ResponseEntity<ApiResponse<List<DailyCountResponse>>> getAiCallStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.ok(statsService.getAiCallsByDay(from, to)));
    }

    @Operation(summary = "PostHog: lượt xem trang ($pageview) theo ngày, N ngày gần nhất")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/analytics/pageviews")
    public ResponseEntity<ApiResponse<List<DailyCountResponse>>> getPageviews(
            @RequestParam(defaultValue = "14") int days) {
        return ResponseEntity.ok(ApiResponse.ok(analyticsService.getPageviewsByDay(days)));
    }

    @Operation(summary = "PostHog: số lần phát sinh mỗi event, N ngày gần nhất")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/analytics/events")
    public ResponseEntity<ApiResponse<List<EventCountResponse>>> getEventCounts(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.ok(analyticsService.getEventCounts(days)));
    }

    @Operation(summary = "PostHog: funnel chuyển đổi (số người dùng đạt mỗi bước), N ngày gần nhất")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/analytics/funnel")
    public ResponseEntity<ApiResponse<List<EventCountResponse>>> getFunnel(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(ApiResponse.ok(analyticsService.getFunnel(days)));
    }
}
