package com.tranbac.chiptripbe.module.user.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.common.security.UserPrincipal;
import com.tranbac.chiptripbe.module.user.dto.response.EntitlementsResponse;
import com.tranbac.chiptripbe.module.user.service.EntitlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Entitlements", description = "Quyền & giới hạn theo tier của người dùng")
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class EntitlementController {

    private final EntitlementService entitlementService;

    @Operation(summary = "Lấy quyền & giới hạn (gate UI Premium/Normal)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/entitlements")
    public ResponseEntity<ApiResponse<EntitlementsResponse>> getEntitlements(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(entitlementService.getEntitlements(principal.getId())));
    }
}
