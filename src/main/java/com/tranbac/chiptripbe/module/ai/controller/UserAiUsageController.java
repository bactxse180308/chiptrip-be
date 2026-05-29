package com.tranbac.chiptripbe.module.ai.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.common.security.UserPrincipal;
import com.tranbac.chiptripbe.module.ai.dto.response.UserAiUsageResponse;
import com.tranbac.chiptripbe.module.ai.service.AiUsageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "AI — User", description = "Lịch sử sử dụng AI của người dùng")
@RestController
@RequestMapping("/api/v1/users/me/ai-usage")
@RequiredArgsConstructor
public class UserAiUsageController {

    private final AiUsageService aiUsageService;

    @Operation(summary = "Lịch sử lượt AI đã dùng")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ResponseEntity<ApiResponse<UserAiUsageResponse>> getMyAiUsage(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(aiUsageService.getUserAiUsage(principal.getId())));
    }
}
