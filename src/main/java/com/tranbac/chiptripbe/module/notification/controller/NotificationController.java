package com.tranbac.chiptripbe.module.notification.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.common.response.PageMeta;
import com.tranbac.chiptripbe.common.security.UserPrincipal;
import com.tranbac.chiptripbe.module.notification.dto.NotificationDto;
import com.tranbac.chiptripbe.module.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "Notifications", description = "Thông báo trong ứng dụng")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "Danh sách thông báo của user hiện tại (mới nhất trước)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ApiResponse<List<NotificationDto>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        Page<NotificationDto> result = notificationService.list(principal.getId(), pageable);
        return ApiResponse.ok(result.getContent(), PageMeta.of(result));
    }

    @Operation(summary = "Số thông báo chưa đọc (cho badge)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Long>> unreadCount(@AuthenticationPrincipal UserPrincipal principal) {
        long count = notificationService.unreadCount(principal.getId());
        return ApiResponse.ok(Map.of("count", count));
    }

    @Operation(summary = "Đánh dấu 1 thông báo đã đọc")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/{id}/read")
    public ApiResponse<Void> markRead(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        notificationService.markRead(principal.getId(), id);
        return ApiResponse.noContent();
    }

    @Operation(summary = "Đánh dấu tất cả thông báo đã đọc")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/read-all")
    public ApiResponse<Void> markAllRead(@AuthenticationPrincipal UserPrincipal principal) {
        notificationService.markAllRead(principal.getId());
        return ApiResponse.noContent();
    }
}
