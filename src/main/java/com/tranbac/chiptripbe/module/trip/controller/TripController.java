package com.tranbac.chiptripbe.module.trip.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.common.response.PageMeta;
import com.tranbac.chiptripbe.common.security.UserPrincipal;
import com.tranbac.chiptripbe.module.trip.dto.request.GenerateTripRequest;
import com.tranbac.chiptripbe.module.trip.dto.request.UpdateTripRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.ShareTokenResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripDetailResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripGenerateResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripSummaryResponse;
import com.tranbac.chiptripbe.module.trip.service.TripExportService;
import com.tranbac.chiptripbe.module.trip.service.TripService;
import com.tranbac.chiptripbe.module.trip.service.impl.AsyncTripGenerator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Trips", description = "Quản lý chuyến đi")
@RestController
@RequestMapping("/api/v1/trips")
@RequiredArgsConstructor
public class TripController {

    private final TripService tripService;
    private final TripExportService tripExportService;
    private final AsyncTripGenerator asyncTripGenerator;

    @Operation(summary = "Sinh lịch trình bằng AI (trừ 1 lượt AI)")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ApiResponse<TripGenerateResponse>> generateTrip(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody GenerateTripRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(tripService.generateTrip(principal.getId(), request)));
    }

    @Operation(summary = "Sinh lịch trình bằng AI (bất đồng bộ — kết quả đẩy qua WebSocket /user/queue/trip-generation)")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/generate-async")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResponseEntity<ApiResponse<Void>> generateTripAsync(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody GenerateTripRequest request) {
        // Validate đồng bộ để fail-fast (402/400/403) + dính rate limit; việc nặng chạy ở luồng nền.
        Long userId = principal.getId();
        tripService.validateGenerateRequest(userId, request);
        asyncTripGenerator.begin(userId);   // 409 nếu user đang có job generate khác chạy → chống tạo trùng/trừ trùng
        try {
            asyncTripGenerator.run(userId, request);
        } catch (RuntimeException e) {
            asyncTripGenerator.release(userId);   // submit async thất bại → nhả khóa, không kẹt
            throw e;
        }
        return ResponseEntity.accepted().body(ApiResponse.noContent());
    }

    @Operation(summary = "Danh sách chuyến đi của tôi")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ResponseEntity<ApiResponse<java.util.List<TripSummaryResponse>>> getMyTrips(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<TripSummaryResponse> result = tripService.getMyTrips(
                principal.getId(),
                PageRequest.of(page, size, Sort.by("createdAt").descending()));
        return ResponseEntity.ok(ApiResponse.ok(result.getContent(), PageMeta.of(result)));
    }

    @Operation(summary = "Chi tiết chuyến đi (bao gồm ngày, hoạt động, checklist)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TripDetailResponse>> getTripDetail(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(tripService.getTripDetail(principal.getId(), id)));
    }

    @Operation(summary = "Cập nhật thông tin chuyến đi")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<TripDetailResponse>> updateTrip(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody UpdateTripRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(tripService.updateTrip(principal.getId(), id, request)));
    }

    @Operation(summary = "Xoá chuyến đi")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<ApiResponse<Void>> deleteTrip(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        tripService.deleteTrip(principal.getId(), id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).body(ApiResponse.noContent());
    }

    @Operation(summary = "Nhân bản chuyến đi")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/clone")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ApiResponse<TripDetailResponse>> cloneTrip(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(tripService.cloneTrip(principal.getId(), id)));
    }

    @Operation(summary = "Bật chia sẻ chuyến đi")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/share")
    public ResponseEntity<ApiResponse<ShareTokenResponse>> enableShare(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(tripService.enableShare(principal.getId(), id)));
    }

    @Operation(summary = "Tắt chia sẻ chuyến đi")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{id}/share")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<ApiResponse<Void>> disableShare(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        tripService.disableShare(principal.getId(), id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).body(ApiResponse.noContent());
    }

    @Operation(summary = "Xem chuyến đi qua link chia sẻ (công khai)")
    @GetMapping("/shared/{shareToken}")
    public ResponseEntity<ApiResponse<TripDetailResponse>> getSharedTrip(@PathVariable String shareToken) {
        return ResponseEntity.ok(ApiResponse.ok(tripService.getSharedTrip(shareToken)));
    }

    @Operation(summary = "Xuất lịch trình ra file PDF")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{id}/export/pdf")
    public ResponseEntity<byte[]> exportPdf(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        byte[] pdf = tripExportService.exportPdf(principal.getId(), id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"chuyen-di-" + id + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
