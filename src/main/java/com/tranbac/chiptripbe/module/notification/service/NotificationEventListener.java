package com.tranbac.chiptripbe.module.notification.service;

import com.tranbac.chiptripbe.module.notification.dto.NotificationDto;
import com.tranbac.chiptripbe.module.notification.entity.Notification;
import com.tranbac.chiptripbe.module.notification.enums.NotificationType;
import com.tranbac.chiptripbe.module.notification.event.AiCreditsLowEvent;
import com.tranbac.chiptripbe.module.notification.event.NewSupportMessageEvent;
import com.tranbac.chiptripbe.module.notification.event.PostTripEvent;
import com.tranbac.chiptripbe.module.notification.event.SupportReplyEvent;
import com.tranbac.chiptripbe.module.notification.event.TripCommentedEvent;
import com.tranbac.chiptripbe.module.notification.event.TripLikedEvent;
import com.tranbac.chiptripbe.module.notification.event.TripMemberAddedEvent;
import com.tranbac.chiptripbe.module.notification.event.TripMemberJoinedEvent;
import com.tranbac.chiptripbe.module.notification.event.TripReminderEvent;
import com.tranbac.chiptripbe.module.notification.event.WeatherAlertEvent;
import com.tranbac.chiptripbe.module.notification.repository.NotificationRepository;
import com.tranbac.chiptripbe.module.user.entity.User;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Lắng nghe domain events và tạo Notification + đẩy WebSocket.
 *
 * Quy tắc:
 * - Dùng @TransactionalEventListener(AFTER_COMMIT) cho event phát ra trong transaction nghiệp vụ
 *   (TripMemberAdded, AiCreditsLow) để không tạo noti khi business transaction rollback.
 * - Dùng @EventListener thông thường cho event phát ra ngoài transaction (TripReminder, WeatherAlert
 *   từ scheduler).
 * - Listener làm 2 việc theo thứ tự: [1] save DB (luôn), [2] đẩy WS (best-effort).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private static final String DESTINATION = "/queue/notifications";

    private final NotificationService notificationService;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTripMemberAdded(TripMemberAddedEvent e) {
        String title = "Bạn được mời vào chuyến đi";
        String body = String.format("%s đã thêm bạn vào '%s'",
                e.inviterName() != null ? e.inviterName() : "Ai đó", e.tripTitle());
        Notification n = notificationService.create(
                e.recipientUserId(), NotificationType.TRIP_MEMBER_ADDED, title, body, e.tripId());
        push(e.recipientUserId(), n);
    }

    /** Có người tự tham gia qua link mời → noti cho owner (tái dùng type TRIP_MEMBER_ADDED). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTripMemberJoined(TripMemberJoinedEvent e) {
        String title = "Thành viên mới tham gia";
        String body = String.format("%s đã tham gia '%s' qua link mời",
                e.joinerName() != null ? e.joinerName() : "Ai đó", e.tripTitle());
        Notification n = notificationService.create(
                e.recipientUserId(), NotificationType.TRIP_MEMBER_ADDED, title, body, e.tripId());
        push(e.recipientUserId(), n);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAiCreditsLow(AiCreditsLowEvent e) {
        String title = "Sắp hết lượt AI";
        String body = String.format("Bạn còn %d lượt tạo lịch trình. Nạp thêm để tiếp tục.",
                e.remainingCredits());
        Notification n = notificationService.create(
                e.userId(), NotificationType.AI_CREDITS_LOW, title, body, null);
        push(e.userId(), n);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSupportReply(SupportReplyEvent e) {
        Notification n = notificationService.create(
                e.recipientUserId(), NotificationType.SUPPORT_REPLY,
                "Hỗ trợ đã phản hồi", e.previewBody(), e.conversationId());
        push(e.recipientUserId(), n);
    }

    /**
     * User gửi tin → tạo Notification NEW_SUPPORT_MESSAGE cho tất cả admin active.
     * Dedup: nếu admin còn 1 noti chưa đọc về conversation này thì bỏ qua (tránh spam khi
     * user gửi liên tiếp).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onNewSupportMessage(NewSupportMessageEvent e) {
        String title = "Tin mới từ " + (e.senderName() != null ? e.senderName() : "khách");
        List<User> admins = userRepository.findAllByRole_NameAndIsActiveTrue(ROLE_ADMIN);
        for (User admin : admins) {
            boolean alreadyPending = notificationRepository
                    .existsByRecipientIdAndTypeAndRefIdAndIsReadFalse(
                            admin.getId(), NotificationType.NEW_SUPPORT_MESSAGE, e.conversationId());
            if (alreadyPending) continue;
            Notification n = notificationService.create(
                    admin.getId(), NotificationType.NEW_SUPPORT_MESSAGE,
                    title, e.previewBody(), e.conversationId());
            push(admin.getId(), n);
        }
    }

    /**
     * Trip được thả tim → noti cho chủ trip.
     * Dedup: còn noti TRIP_LIKED chưa đọc cho trip này thì bỏ qua (tránh spam khi toggle liên tục).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTripLiked(TripLikedEvent e) {
        boolean alreadyPending = notificationRepository
                .existsByRecipientIdAndTypeAndRefIdAndIsReadFalse(
                        e.recipientUserId(), NotificationType.TRIP_LIKED, e.tripId());
        if (alreadyPending) return;
        String title = "Lịch trình của bạn được thả tim";
        String body = String.format("%s đã thích '%s'",
                e.likerName() != null ? e.likerName() : "Ai đó", e.tripTitle());
        Notification n = notificationService.create(
                e.recipientUserId(), NotificationType.TRIP_LIKED, title, body, e.tripId());
        push(e.recipientUserId(), n);
    }

    /** Trip có comment mới → noti cho chủ trip; reply → noti cho tác giả comment cha. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTripCommented(TripCommentedEvent e) {
        String commenter = e.commenterName() != null ? e.commenterName() : "Ai đó";
        String title = e.reply()
                ? commenter + " đã trả lời bình luận của bạn"
                : commenter + " đã bình luận lịch trình của bạn";
        String body = String.format("'%s': %s", e.tripTitle(), e.previewBody());
        Notification n = notificationService.create(
                e.recipientUserId(), NotificationType.TRIP_COMMENTED, title, body, e.tripId());
        push(e.recipientUserId(), n);
    }

    /** Scheduler tạo event ngoài transaction nghiệp vụ → dùng EventListener thường. */
    @EventListener
    public void onTripReminder(TripReminderEvent e) {
        String title = "Chuyến đi sắp bắt đầu!";
        String body = String.format("'%s' khởi hành ngày mai (%s).", e.tripTitle(), e.dateStart());
        Notification n = notificationService.create(
                e.recipientUserId(), NotificationType.TRIP_REMINDER, title, body, e.tripId());
        push(e.recipientUserId(), n);
    }

    /** Scheduler tạo event ngoài transaction → EventListener thường. Owner nhấp vào sẽ tới trip của họ. */
    @EventListener
    public void onPostTrip(PostTripEvent e) {
        String title = "Chuyến đi thế nào? 🎒";
        String body = String.format("Đánh giá các địa điểm trong '%s' và chia sẻ lịch trình cho cộng đồng nhé!",
                e.tripTitle());
        Notification n = notificationService.create(
                e.recipientUserId(), NotificationType.POST_TRIP_REVIEW, title, body, e.tripId());
        push(e.recipientUserId(), n);
    }

    @EventListener
    public void onWeatherAlert(WeatherAlertEvent e) {
        String title = "Cảnh báo thời tiết";
        String body = String.format("Chuyến '%s' ngày %s có %s (%s).",
                e.tripTitle(), e.date(), e.condition(), e.description());
        Notification n = notificationService.create(
                e.recipientUserId(), NotificationType.WEATHER_ALERT, title, body, e.tripId());
        push(e.recipientUserId(), n);
    }

    private void push(Long userId, Notification n) {
        // WS push KHÔNG được làm fail nghiệp vụ chính. Lỗi đẩy WS chỉ log warn.
        try {
            messagingTemplate.convertAndSendToUser(
                    String.valueOf(userId), DESTINATION, NotificationDto.from(n));
        } catch (MessagingException ex) {
            log.warn("Failed to push notification {} via WS to userId={}: {}",
                    n.getId(), userId, ex.getMessage());
        }
    }
}
