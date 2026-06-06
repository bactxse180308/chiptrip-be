package com.tranbac.chiptripbe.module.notification.service.impl;

import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.notification.dto.NotificationDto;
import com.tranbac.chiptripbe.module.notification.entity.Notification;
import com.tranbac.chiptripbe.module.notification.enums.NotificationType;
import com.tranbac.chiptripbe.module.notification.repository.NotificationRepository;
import com.tranbac.chiptripbe.module.notification.service.NotificationService;
import com.tranbac.chiptripbe.module.user.entity.User;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public Notification create(Long recipientUserId, NotificationType type, String title, String body, Long refId) {
        User recipient = userRepository.findById(recipientUserId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy người nhận thông báo"));
        Notification n = Notification.builder()
                .recipient(recipient)
                .type(type)
                .title(title)
                .body(body)
                .refId(refId)
                .isRead(false)
                .build();
        n = notificationRepository.save(n);
        log.info("Created notification id={} type={} for userId={}", n.getId(), type, recipientUserId);
        return n;
    }

    @Override
    public Page<NotificationDto> list(Long userId, Pageable pageable) {
        return notificationRepository.findByRecipientIdOrderByCreatedAtDesc(userId, pageable)
                .map(NotificationDto::from);
    }

    @Override
    public long unreadCount(Long userId) {
        return notificationRepository.countByRecipientIdAndIsReadFalse(userId);
    }

    @Override
    @Transactional
    public void markRead(Long userId, Long notificationId) {
        Notification n = notificationRepository.findById(notificationId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy thông báo"));
        // Anti-IDOR: notification phải thuộc về user. Trả 404 để không leak existence.
        if (!n.getRecipient().getId().equals(userId)) {
            throw AppException.notFound("Không tìm thấy thông báo");
        }
        if (!n.isRead()) {
            n.setRead(true);
            notificationRepository.save(n);
        }
    }

    @Override
    @Transactional
    public void markAllRead(Long userId) {
        int updated = notificationRepository.markAllReadByRecipient(userId);
        log.debug("Marked {} notifications as read for userId={}", updated, userId);
    }
}
