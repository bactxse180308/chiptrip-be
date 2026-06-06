package com.tranbac.chiptripbe.module.chat.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.common.response.PageMeta;
import com.tranbac.chiptripbe.common.security.UserPrincipal;
import com.tranbac.chiptripbe.module.chat.dto.request.SendMessageRequest;
import com.tranbac.chiptripbe.module.chat.dto.response.AdminConversationDto;
import com.tranbac.chiptripbe.module.chat.dto.response.MessageDto;
import com.tranbac.chiptripbe.module.chat.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "Admin Chat", description = "Quản trị viên xem và trả lời chat hỗ trợ")
@RestController
@RequestMapping("/api/v1/admin/chat")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminChatController {

    private final ChatService chatService;

    @Operation(summary = "Danh sách hội thoại (mặc định status=OPEN), sort lastMessageAt desc")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/conversations")
    public ApiResponse<List<AdminConversationDto>> list(
            @RequestParam(required = false, defaultValue = "OPEN") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        Page<AdminConversationDto> result = chatService.listConversationsForAdmin(status, pageable);
        return ApiResponse.ok(result.getContent(), PageMeta.of(result));
    }

    @Operation(summary = "Lịch sử tin nhắn của 1 hội thoại (cursor)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<List<MessageDto>> history(
            @PathVariable Long id,
            @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(chatService.historyForAdmin(id, before, size));
    }

    @Operation(summary = "Admin trả lời text")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MessageDto> reply(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody SendMessageRequest req) {
        return ApiResponse.created(chatService.sendTextAsAdmin(principal.getId(), id, req.content()));
    }

    @Operation(summary = "Admin gửi ảnh")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping(value = "/conversations/{id}/messages/image", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MessageDto> replyImage(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.created(chatService.sendImageAsAdmin(principal.getId(), id, file));
    }

    @Operation(summary = "Đánh dấu đã đọc đến tin mới nhất")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/conversations/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id) {
        chatService.markReadByAdmin(id);
        return ApiResponse.noContent();
    }

    @Operation(summary = "Đóng hội thoại")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/conversations/{id}/close")
    public ApiResponse<Void> close(@PathVariable Long id) {
        chatService.closeConversation(id);
        return ApiResponse.noContent();
    }
}
