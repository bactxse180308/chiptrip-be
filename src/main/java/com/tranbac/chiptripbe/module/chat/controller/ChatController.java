package com.tranbac.chiptripbe.module.chat.controller;

import com.tranbac.chiptripbe.common.response.ApiResponse;
import com.tranbac.chiptripbe.common.security.UserPrincipal;
import com.tranbac.chiptripbe.module.chat.dto.request.SendMessageRequest;
import com.tranbac.chiptripbe.module.chat.dto.response.ConversationDto;
import com.tranbac.chiptripbe.module.chat.dto.response.MessageDto;
import com.tranbac.chiptripbe.module.chat.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Chat hỗ trợ phía user. Server tự suy conversation từ JWT, KHÔNG nhận
 * conversationId từ client để chống IDOR.
 */
@Tag(name = "Chat", description = "Chat hỗ trợ giữa người dùng và quản trị viên")
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @Operation(summary = "Lấy hoặc tạo hội thoại hỗ trợ của tôi")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/conversation")
    public ApiResponse<ConversationDto> myConversation(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(chatService.getOrCreateForUser(principal.getId()));
    }

    @Operation(summary = "Lịch sử tin nhắn của tôi (cursor: before id, mới→cũ)")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/conversation/messages")
    public ApiResponse<List<MessageDto>> myHistory(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(chatService.historyForUser(principal.getId(), before, size));
    }

    @Operation(summary = "Gửi tin nhắn text")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MessageDto> send(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody SendMessageRequest req) {
        return ApiResponse.created(chatService.sendTextAsUser(principal.getId(), req.content()));
    }

    @Operation(summary = "Gửi ảnh (multipart, field name = file)")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping(value = "/messages/image", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MessageDto> sendImage(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.created(chatService.sendImageAsUser(principal.getId(), file));
    }

    @Operation(summary = "Đánh dấu đã đọc đến tin mới nhất")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/conversation/read")
    public ApiResponse<Void> markRead(@AuthenticationPrincipal UserPrincipal principal) {
        chatService.markReadByUser(principal.getId());
        return ApiResponse.noContent();
    }
}
