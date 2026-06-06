package com.tranbac.chiptripbe.module.notification.event;

/**
 * Phát ra khi admin trả lời 1 hội thoại hỗ trợ.
 * Listener tạo Notification SUPPORT_REPLY ở phase AFTER_COMMIT để
 * không kéo chat transaction rollback nếu notification fail.
 */
public record SupportReplyEvent(Long recipientUserId, Long conversationId, String previewBody) {
}
