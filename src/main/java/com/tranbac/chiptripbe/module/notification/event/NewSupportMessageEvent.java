package com.tranbac.chiptripbe.module.notification.event;

/**
 * Phát ra khi user gửi tin mới vào hội thoại hỗ trợ.
 * Listener tạo Notification NEW_SUPPORT_MESSAGE cho mỗi admin (dedup 1 hội thoại = 1 noti chưa đọc).
 */
public record NewSupportMessageEvent(Long conversationId, String senderName, String previewBody) {
}
