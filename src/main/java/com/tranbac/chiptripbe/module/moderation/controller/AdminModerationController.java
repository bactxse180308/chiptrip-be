package com.tranbac.chiptripbe.module.moderation.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.common.response.PageMeta;
import com.tranbac.chiptripbe.common.security.UserPrincipal;
import com.tranbac.chiptripbe.module.moderation.dto.request.ResolveReportRequest;
import com.tranbac.chiptripbe.module.moderation.dto.response.ReportResponse;
import com.tranbac.chiptripbe.module.moderation.enums.ReportStatus;
import com.tranbac.chiptripbe.module.moderation.service.ModerationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "Admin — Moderation", description = "Kiểm duyệt nội dung báo cáo")
@RestController
@RequestMapping("/api/v1/admin/reports")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminModerationController {

    private final ModerationService moderationService;

    @Operation(summary = "Danh sách báo cáo (lọc theo status)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ResponseEntity<ApiResponse<List<ReportResponse>>> list(
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ReportResponse> result = moderationService.adminList(status, PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.ok(result.getContent(), PageMeta.of(result)));
    }

    @Operation(summary = "Số báo cáo đang chờ xử lý (cho badge)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/pending-count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> pendingCount() {
        return ResponseEntity.ok(ApiResponse.ok(Map.of("count", moderationService.adminCountPending())));
    }

    @Operation(summary = "Xử lý báo cáo: xóa nội dung hoặc bỏ qua")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/{reportId}/resolve")
    public ResponseEntity<ApiResponse<ReportResponse>> resolve(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long reportId,
            @Valid @RequestBody ResolveReportRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                moderationService.adminResolve(principal.getId(), reportId, request.getAction())));
    }
}
