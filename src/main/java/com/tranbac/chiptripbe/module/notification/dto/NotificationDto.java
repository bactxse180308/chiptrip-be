package com.tranbac.chiptripbe.module.notification.dto;

import com.tranbac.chiptripbe.module.notification.entity.Notification;
import com.tranbac.chiptripbe.module.notification.enums.NotificationType;

import java.time.LocalDateTime;

public record NotificationDto(
        Long id,
        NotificationType type,
        String title,
        String body,
        Long refId,
        boolean isRead,
        LocalDateTime createdAt
) {
    public static NotificationDto from(Notification n) {
        return new NotificationDto(
                n.getId(),
                n.getType(),
                n.getTitle(),
                n.getBody(),
                n.getRefId(),
                n.isRead(),
                n.getCreatedAt()
        );
    }
}
