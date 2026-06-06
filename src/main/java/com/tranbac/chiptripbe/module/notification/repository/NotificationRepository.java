package com.tranbac.chiptripbe.module.notification.repository;

import com.tranbac.chiptripbe.module.notification.entity.Notification;
import com.tranbac.chiptripbe.module.notification.enums.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByRecipientIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Optional<Notification> findByIdAndRecipientId(Long id, Long recipientId);

    long countByRecipientIdAndIsReadFalse(Long userId);

    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.recipient.id = :userId AND n.isRead = false")
    int markAllReadByRecipient(@Param("userId") Long userId);

    /** Dùng cho WEATHER_ALERT dedup theo trip+ngày (refId = tripId, message chứa ISO date). */
    boolean existsByRecipientIdAndTypeAndRefIdAndBodyContaining(Long userId, NotificationType type, Long refId, String bodyFragment);

    /** Dedup NEW_SUPPORT_MESSAGE: nếu admin còn 1 noti chưa đọc về conversation này thì không tạo thêm. */
    boolean existsByRecipientIdAndTypeAndRefIdAndIsReadFalse(Long userId, NotificationType type, Long refId);
}
