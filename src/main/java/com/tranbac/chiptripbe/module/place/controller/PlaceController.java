package com.tranbac.chiptripbe.module.place.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.module.place.dto.PhotoFailureReportRequest;
import com.tranbac.chiptripbe.module.place.dto.PlaceDto;
import com.tranbac.chiptripbe.module.place.service.PlacePhotoService;
import com.tranbac.chiptripbe.module.place.service.PlaceQueryService;
import com.tranbac.chiptripbe.module.place.service.impl.PlacePhotoRefreshWorker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoint lấy chi tiết địa điểm đã enrich (rating, ảnh, giờ mở cửa).
 * Frontend gọi lazy khi user mở chi tiết một activity trong lịch trình.
 */
@Tag(name = "Places", description = "Thông tin địa điểm đã enrich từ Goong + SerpApi")
@RestController
@RequestMapping("/api/v1/places")
@RequiredArgsConstructor
public class PlaceController {

    private final PlaceQueryService placeQueryService;
    private final PlacePhotoService placePhotoService;
    private final PlacePhotoRefreshWorker placePhotoRefreshWorker;

    @Operation(summary = "Lấy chi tiết địa điểm theo cache ID")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{id}")
    public ApiResponse<PlaceDto> getPlace(@PathVariable Long id) {
        return ApiResponse.ok(placeQueryService.getPlace(id));
    }

    @Operation(summary = "Bao URL anh dia diem bi loi va yeu cau refresh nen")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{id}/photos/failures")
    public ResponseEntity<ApiResponse<Void>> reportPhotoFailure(
            @PathVariable Long id,
            @Valid @RequestBody PhotoFailureReportRequest request) {
        boolean accepted = placePhotoService.reportFailure(id, request.url());
        if (accepted) {
            placePhotoRefreshWorker.refreshAsync(id);
        }
        return ResponseEntity.accepted().body(ApiResponse.ok(
                null,
                accepted ? "Da ghi nhan anh loi va len lich dong bo lai" : "Anh loi da duoc ghi nhan truoc do"));
    }
}
