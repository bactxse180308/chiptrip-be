package com.tranbac.chiptripbe.module.chat.dto.response;

import com.tranbac.chiptripbe.module.chat.entity.Conversation;
import com.tranbac.chiptripbe.module.chat.enums.ConversationStatus;

import java.time.LocalDateTime;

public record ConversationDto(
        Long id,
        ConversationStatus status,
        LocalDateTime lastMessageAt,
        long unreadCount
) {
    public static ConversationDto from(Conversation c, long unreadCount) {
        return new ConversationDto(c.getId(), c.getStatus(), c.getLastMessageAt(), unreadCount);
    }
}
