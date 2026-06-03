package com.tranbac.chiptripbe.module.trip.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.common.security.UserPrincipal;
import com.tranbac.chiptripbe.module.trip.dto.request.CreateActivityRequest;
import com.tranbac.chiptripbe.module.trip.dto.request.ReorderActivitiesRequest;
import com.tranbac.chiptripbe.module.trip.dto.request.UpdateActivityRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.TripDetailResponse;
import com.tranbac.chiptripbe.module.trip.service.ActivityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Activities", description = "Quản lý hoạt động trong chuyến đi")
@RestController
@RequestMapping("/api/v1/trips/{tripId}/days/{dayId}/activities")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;

    @Operation(summary = "Thêm hoạt động vào ngày")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ApiResponse<TripDetailResponse.ActivityDetail>> addActivity(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long tripId,
            @PathVariable Long dayId,
            @Valid @RequestBody CreateActivityRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(activityService.addActivity(principal.getId(), tripId, dayId, request)));
    }

    @Operation(summary = "Cập nhật hoạt động")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/{activityId}")
    public ResponseEntity<ApiResponse<TripDetailResponse.ActivityDetail>> updateActivity(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long tripId,
            @PathVariable Long dayId,
            @PathVariable Long activityId,
            @Valid @RequestBody UpdateActivityRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                activityService.updateActivity(principal.getId(), tripId, dayId, activityId, request)));
    }

    @Operation(summary = "Xoá hoạt động")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{activityId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<ApiResponse<Void>> deleteActivity(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long tripId,
            @PathVariable Long dayId,
            @PathVariable Long activityId) {
        activityService.deleteActivity(principal.getId(), tripId, dayId, activityId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).body(ApiResponse.noContent());
    }

    @Operation(summary = "Sắp xếp lại thứ tự hoạt động")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/reorder")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<ApiResponse<Void>> reorderActivities(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long tripId,
            @PathVariable Long dayId,
            @Valid @RequestBody ReorderActivitiesRequest request) {
        activityService.reorderActivities(principal.getId(), tripId, dayId, request);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).body(ApiResponse.noContent());
    }
}
