package com.tranbac.chiptripbe.module.chat.dto.response;

import com.tranbac.chiptripbe.module.chat.entity.Conversation;

import java.time.LocalDateTime;

public record AdminConversationDto(
        Long id,
        Long userId,
        String userName,
        String userEmail,
        LocalDateTime lastMessageAt,
        String lastMessagePreview,
        long unreadCount
) {
    public static AdminConversationDto from(Conversation c, String preview, long unreadCount) {
        return new AdminConversationDto(
                c.getId(),
                c.getUser().getId(),
                c.getUser().getFullName(),
                c.getUser().getEmail(),
                c.getLastMessageAt(),
                preview,
                unreadCount
        );
    }
}
