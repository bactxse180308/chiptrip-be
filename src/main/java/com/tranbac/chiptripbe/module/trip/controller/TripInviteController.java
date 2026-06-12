package com.tranbac.chiptripbe.module.trip.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.common.security.UserPrincipal;
import com.tranbac.chiptripbe.module.trip.dto.response.InviteTokenResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripInvitePreviewResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripMemberResponse;
import com.tranbac.chiptripbe.module.trip.service.TripMemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Trip Invites", description = "Mời thành viên qua link")
@RestController
@RequestMapping("/api/v1/trips")
@RequiredArgsConstructor
public class TripInviteController {

    private final TripMemberService tripMemberService;

    @Operation(summary = "Tạo link mời (owner only, idempotent)")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{tripId}/invite")
    public ResponseEntity<ApiResponse<InviteTokenResponse>> createInvite(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long tripId) {
        String token = tripMemberService.createInvite(principal.getId(), tripId);
        return ResponseEntity.ok(ApiResponse.ok(InviteTokenResponse.builder().inviteToken(token).build()));
    }

    @Operation(summary = "Thu hồi link mời (owner only)")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{tripId}/invite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<ApiResponse<Void>> revokeInvite(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long tripId) {
        tripMemberService.revokeInvite(principal.getId(), tripId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).body(ApiResponse.noContent());
    }

    @Operation(summary = "Preview chuyến đi theo link mời (public — thông tin tối thiểu, không lộ ngày)")
    @GetMapping("/invite/{inviteToken}")
    public ResponseEntity<ApiResponse<TripInvitePreviewResponse>> getInvitePreview(
            @PathVariable String inviteToken) {
        return ResponseEntity.ok(ApiResponse.ok(tripMemberService.getInvitePreview(inviteToken)));
    }

    @Operation(summary = "Tham gia chuyến đi qua link mời (409 nếu đã là thành viên)")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/join/{inviteToken}")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ApiResponse<TripMemberResponse>> joinByInvite(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String inviteToken) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(tripMemberService.joinByInvite(principal.getId(), inviteToken)));
    }
}
