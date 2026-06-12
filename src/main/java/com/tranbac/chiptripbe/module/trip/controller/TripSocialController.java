package com.tranbac.chiptripbe.module.trip.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.common.response.PageMeta;
import com.tranbac.chiptripbe.common.security.UserPrincipal;
import com.tranbac.chiptripbe.module.trip.dto.request.AddCommentRequest;
import com.tranbac.chiptripbe.module.trip.dto.request.TripPublishRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.LikeResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripCommentResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripDetailResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripPublicSummaryResponse;
import com.tranbac.chiptripbe.module.trip.service.TripService;
import com.tranbac.chiptripbe.module.trip.service.TripSocialService;
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

@Tag(name = "Trip Social", description = "Trip công khai, like, comment")
@RestController
@RequestMapping("/api/v1/trips")
@RequiredArgsConstructor
public class TripSocialController {

    private final TripSocialService tripSocialService;
    private final TripService tripService;

    @Operation(summary = "Feed trip công khai (lọc theo destination)")
    @GetMapping("/public/feed")
    public ResponseEntity<ApiResponse<List<TripPublicSummaryResponse>>> getPublicFeed(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) String destination,
            @RequestParam(defaultValue = "latest") String sort) {
        Page<TripPublicSummaryResponse> result =
                tripSocialService.getPublicFeed(PageRequest.of(page, size), destination, sort);
        return ResponseEntity.ok(ApiResponse.ok(result.getContent(), PageMeta.of(result)));
    }

    @Operation(summary = "Xem chi tiết trip công khai (read-only)")
    @GetMapping("/{tripId}/public")
    public ResponseEntity<ApiResponse<TripDetailResponse>> getPublicTrip(@PathVariable Long tripId) {
        return ResponseEntity.ok(ApiResponse.ok(tripService.getPublicTrip(tripId)));
    }

    @Operation(summary = "Đăng / hủy công khai chuyến đi")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/{tripId}/publish")
    public ResponseEntity<ApiResponse<TripPublicSummaryResponse>> publishTrip(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long tripId,
            @Valid @RequestBody TripPublishRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                tripSocialService.publishTrip(principal.getId(), tripId, request.getIsPublic())));
    }

    @Operation(summary = "Toggle like trip công khai")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{tripId}/like")
    public ResponseEntity<ApiResponse<LikeResponse>> toggleLike(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long tripId) {
        return ResponseEntity.ok(ApiResponse.ok(tripSocialService.toggleLike(principal.getId(), tripId)));
    }

    @Operation(summary = "Trạng thái like hiện tại của tôi")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{tripId}/like")
    public ResponseEntity<ApiResponse<LikeResponse>> getLikeStatus(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long tripId) {
        return ResponseEntity.ok(ApiResponse.ok(tripSocialService.getLikeStatus(principal.getId(), tripId)));
    }

    @Operation(summary = "Danh sách comment dạng tree (public)")
    @GetMapping("/{tripId}/comments")
    public ResponseEntity<ApiResponse<List<TripCommentResponse>>> getComments(
            @PathVariable Long tripId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<TripCommentResponse> result =
                tripSocialService.getComments(tripId, PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.ok(result.getContent(), PageMeta.of(result)));
    }

    @Operation(summary = "Thêm comment / reply")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{tripId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ApiResponse<TripCommentResponse>> addComment(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long tripId,
            @Valid @RequestBody AddCommentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(tripSocialService.addComment(principal.getId(), tripId, request)));
    }

    @Operation(summary = "Xóa comment (tác giả hoặc chủ trip)")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{tripId}/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<ApiResponse<Void>> deleteComment(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long tripId,
            @PathVariable Long commentId) {
        tripSocialService.deleteComment(principal.getId(), tripId, commentId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).body(ApiResponse.noContent());
    }
}
