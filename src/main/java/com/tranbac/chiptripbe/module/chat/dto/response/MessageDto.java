package com.tranbac.chiptripbe.module.chat.dto.response;

import com.tranbac.chiptripbe.module.chat.entity.ChatMessage;
import com.tranbac.chiptripbe.module.chat.enums.MessageType;
import com.tranbac.chiptripbe.module.chat.enums.SenderRole;

import java.time.LocalDateTime;

public record MessageDto(
        Long id,
        Long conversationId,
        SenderRole senderRole,
        MessageType messageType,
        String content,
        String imageUrl,
        LocalDateTime createdAt
) {
    public static MessageDto from(ChatMessage m) {
        return new MessageDto(
                m.getId(),
                m.getConversation().getId(),
                m.getSenderRole(),
                m.getMessageType(),
                m.getContent(),
                m.getImageUrl(),
                m.getCreatedAt()
        );
    }
}
