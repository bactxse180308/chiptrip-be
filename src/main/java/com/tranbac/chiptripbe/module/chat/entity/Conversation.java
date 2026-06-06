package com.tranbac.chiptripbe.module.chat.entity;

import com.tranbac.chiptripbe.common.entity.BaseAuditEntity;
import com.tranbac.chiptripbe.module.chat.enums.ConversationStatus;
import com.tranbac.chiptripbe.module.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Nationalized;

import java.time.LocalDateTime;

/**
 * Hội thoại hỗ trợ giữa 1 user và admin.
 *
 * Ràng buộc "1 user = 1 hội thoại đang mở" được enforce ở SERVICE
 * (getOrCreateActiveConversation), KHÔNG đặt unique trên user_id để có thể
 * mở rộng thành nhiều ticket sau này mà không phải đổi schema.
 *
 * subject / assignedAdminId hiện chưa dùng — để sẵn cho ticket sau này.
 */
@Entity
@Table(name = "conversations",
        indexes = {
                @Index(name = "ix_conversations_user_status", columnList = "user_id, status"),
                @Index(name = "ix_conversations_status_last_msg", columnList = "status, last_message_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Conversation extends BaseAuditEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_conversations_user"))
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ConversationStatus status = ConversationStatus.OPEN;

    @Column(name = "last_message_at", columnDefinition = "DATETIME2")
    private LocalDateTime lastMessageAt;

    @Column(name = "last_read_by_user_msg_id")
    private Long lastReadByUserMsgId;

    @Column(name = "last_read_by_admin_msg_id")
    private Long lastReadByAdminMsgId;

    @Nationalized
    @Column(name = "subject", length = 200)
    private String subject;

    @Column(name = "assigned_admin_id")
    private Long assignedAdminId;
}
