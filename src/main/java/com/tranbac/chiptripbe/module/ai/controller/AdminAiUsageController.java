package com.tranbac.chiptripbe.module.ai.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.common.response.PageMeta;
import com.tranbac.chiptripbe.module.ai.dto.response.AiUsageResponse;
import com.tranbac.chiptripbe.module.ai.service.AdminAiUsageService;
import com.tranbac.chiptripbe.module.stats.dto.response.AiCostByProviderMonthResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Admin — AI Usages", description = "Quản trị log sử dụng AI")
@RestController
@RequestMapping("/api/v1/admin/ai-usages")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminAiUsageController {

    private final AdminAiUsageService adminAiUsageService;

    @Operation(summary = "Log AI usage (lọc theo userId, provider, from, to)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ResponseEntity<ApiResponse<List<AiUsageResponse>>> getAllUsages(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<AiUsageResponse> result = adminAiUsageService.getAllUsages(
                userId, provider, from, to,
                PageRequest.of(page, size, Sort.by("createdAt").descending()));

        return ResponseEntity.ok(ApiResponse.ok(result.getContent(), PageMeta.of(result)));
    }

    @Operation(summary = "Tổng hợp chi phí AI theo provider và tháng (dùng cho biểu đồ)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<List<AiCostByProviderMonthResponse>>> getSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.ok(adminAiUsageService.getSummary(from, to)));
    }
}
