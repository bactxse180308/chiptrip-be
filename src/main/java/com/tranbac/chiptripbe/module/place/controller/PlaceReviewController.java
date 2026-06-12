package com.tranbac.chiptripbe.module.place.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.common.response.PageMeta;
import com.tranbac.chiptripbe.common.security.UserPrincipal;
import com.tranbac.chiptripbe.module.place.dto.PlaceReviewRequest;
import com.tranbac.chiptripbe.module.place.dto.PlaceReviewResponse;
import com.tranbac.chiptripbe.module.place.dto.PlaceReviewSummaryResponse;
import com.tranbac.chiptripbe.module.place.service.PlaceReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Place Reviews", description = "Đánh giá ChipTrip của user cho địa điểm")
@RestController
@RequestMapping("/api/v1/places/{placeCacheId}/reviews")
@RequiredArgsConstructor
public class PlaceReviewController {

    private final PlaceReviewService placeReviewService;

    @Operation(summary = "Danh sách đánh giá ChipTrip (public, phân trang)")
    @GetMapping
    public ResponseEntity<ApiResponse<List<PlaceReviewResponse>>> getReviews(
            @PathVariable Long placeCacheId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<PlaceReviewResponse> result =
                placeReviewService.getReviews(placeCacheId, PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.ok(result.getContent(), PageMeta.of(result)));
    }

    @Operation(summary = "Rating trung bình + tổng số đánh giá ChipTrip (public)")
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<PlaceReviewSummaryResponse>> getSummary(
            @PathVariable Long placeCacheId) {
        return ResponseEntity.ok(ApiResponse.ok(placeReviewService.getSummary(placeCacheId)));
    }

    @Operation(summary = "Gửi đánh giá (đã có thì cập nhật)")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ApiResponse<PlaceReviewResponse>> upsertReview(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long placeCacheId,
            @Valid @RequestBody PlaceReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(placeReviewService.upsertReview(principal.getId(), placeCacheId, request)));
    }

    @Operation(summary = "Xóa đánh giá của chính mình")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{reviewId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<ApiResponse<Void>> deleteReview(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long placeCacheId,
            @PathVariable Long reviewId) {
        placeReviewService.deleteReview(principal.getId(), placeCacheId, reviewId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).body(ApiResponse.noContent());
    }
}
