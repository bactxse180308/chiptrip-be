package com.tranbac.chiptripbe.module.trip.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.common.security.UserPrincipal;
import com.tranbac.chiptripbe.module.trip.dto.request.AddMemberRequest;
import com.tranbac.chiptripbe.module.trip.dto.request.UpdateMemberRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.TripMemberResponse;
import com.tranbac.chiptripbe.module.trip.service.TripMemberService;
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

@Tag(name = "Trip Members", description = "Quản lý thành viên chuyến đi")
@RestController
@RequestMapping("/api/v1/trips/{tripId}/members")
@RequiredArgsConstructor
public class TripMemberController {

    private final TripMemberService tripMemberService;

    @Operation(summary = "Danh sách thành viên")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ResponseEntity<ApiResponse<List<TripMemberResponse>>> getMembers(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long tripId) {
        return ResponseEntity.ok(ApiResponse.ok(tripMemberService.getMembers(principal.getId(), tripId)));
    }

    @Operation(summary = "Thêm thành viên (owner only)")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ApiResponse<TripMemberResponse>> addMember(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long tripId,
            @Valid @RequestBody AddMemberRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(tripMemberService.addMember(principal.getId(), tripId, request)));
    }

    @Operation(summary = "Đổi tên thành viên (owner only)")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/{memberId}")
    public ResponseEntity<ApiResponse<TripMemberResponse>> updateMember(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long tripId,
            @PathVariable Long memberId,
            @Valid @RequestBody UpdateMemberRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                tripMemberService.updateMember(principal.getId(), tripId, memberId, request)));
    }

    @Operation(summary = "Xóa thành viên (owner only)")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<ApiResponse<Void>> removeMember(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long tripId,
            @PathVariable Long memberId) {
        tripMemberService.removeMember(principal.getId(), tripId, memberId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).body(ApiResponse.noContent());
    }
}
