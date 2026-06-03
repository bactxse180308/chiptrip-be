package com.tranbac.chiptripbe.module.trip.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.common.security.UserPrincipal;
import com.tranbac.chiptripbe.module.trip.dto.request.CreateChecklistItemRequest;
import com.tranbac.chiptripbe.module.trip.dto.request.UpdateChecklistItemRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.TripDetailResponse;
import com.tranbac.chiptripbe.module.trip.service.ChecklistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Checklist", description = "Quản lý checklist chuẩn bị đồ")
@RestController
@RequestMapping("/api/v1/trips/{tripId}/checklist")
@RequiredArgsConstructor
public class ChecklistController {

    private final ChecklistService checklistService;

    @Operation(summary = "Lấy checklist của chuyến đi")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ResponseEntity<ApiResponse<List<TripDetailResponse.ChecklistItemDetail>>> getChecklist(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long tripId) {
        return ResponseEntity.ok(ApiResponse.ok(checklistService.getChecklist(principal.getId(), tripId)));
    }

    @Operation(summary = "Thêm item vào checklist")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ApiResponse<TripDetailResponse.ChecklistItemDetail>> addItem(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long tripId,
            @Valid @RequestBody CreateChecklistItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(checklistService.addChecklistItem(principal.getId(), tripId, request)));
    }

    @Operation(summary = "Cập nhật item trong checklist")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/{itemId}")
    public ResponseEntity<ApiResponse<TripDetailResponse.ChecklistItemDetail>> updateItem(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long tripId,
            @PathVariable Long itemId,
            @Valid @RequestBody UpdateChecklistItemRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                checklistService.updateChecklistItem(principal.getId(), tripId, itemId, request)));
    }

    @Operation(summary = "Toggle checkbox của item")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/{itemId}/toggle")
    public ResponseEntity<ApiResponse<TripDetailResponse.ChecklistItemDetail>> toggleItem(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long tripId,
            @PathVariable Long itemId) {
        return ResponseEntity.ok(ApiResponse.ok(
                checklistService.toggleChecklistItem(principal.getId(), tripId, itemId)));
    }

    @Operation(summary = "Xoá item khỏi checklist")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<ApiResponse<Void>> deleteItem(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long tripId,
            @PathVariable Long itemId) {
        checklistService.deleteChecklistItem(principal.getId(), tripId, itemId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).body(ApiResponse.noContent());
    }
}
