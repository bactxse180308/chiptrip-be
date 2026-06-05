package com.tranbac.chiptripbe.module.ai.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.common.security.UserPrincipal;
import com.tranbac.chiptripbe.module.ai.dto.request.SuggestDestinationsRequest;
import com.tranbac.chiptripbe.module.ai.dto.response.DestinationSuggestion;
import com.tranbac.chiptripbe.module.ai.service.AiSuggestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "AI — Suggestions", description = "Gợi ý điểm đến bằng AI")
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiSuggestController {

    private final AiSuggestService aiSuggestService;

    @Operation(summary = "Gợi ý 3-5 điểm đến phù hợp")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/suggest-destinations")
    public ResponseEntity<ApiResponse<List<DestinationSuggestion>>> suggest(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody SuggestDestinationsRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                aiSuggestService.suggest(principal.getId(), request)));
    }
}
