package com.tranbac.chiptripbe.module.chat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.chat")
@Getter
@Setter
public class ChatProperties {
    /** Giới hạn dung lượng ảnh upload (bytes). Mặc định 5 MB. */
    private long imageMaxBytes = 5L * 1024 * 1024;

    /**
     * Tin tự động trả lời khi user gửi tin đầu tiên vào conversation.
     * Để rỗng/null sẽ tắt auto-reply.
     */
    private String autoReplyMessage = "Xin vui lòng đợi vài phút để nhân viên phản hồi. Cảm ơn bạn đã liên hệ ChipTrip! 🐥";
}
