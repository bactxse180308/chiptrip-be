package com.tranbac.chiptripbe.module.notification.service;

import com.tranbac.chiptripbe.module.notification.dto.NotificationDto;
import com.tranbac.chiptripbe.module.notification.entity.Notification;
import com.tranbac.chiptripbe.module.notification.enums.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface NotificationService {

    /** Tạo + lưu DB; trả entity để caller có thể đẩy WS bản DTO. */
    Notification create(Long recipientUserId, NotificationType type, String title, String body, Long refId);

    Page<NotificationDto> list(Long userId, Pageable pageable);

    long unreadCount(Long userId);

    /** Yêu cầu notification thuộc về userId; nếu không -> 404. */
    void markRead(Long userId, Long notificationId);

    void markAllRead(Long userId);
}
