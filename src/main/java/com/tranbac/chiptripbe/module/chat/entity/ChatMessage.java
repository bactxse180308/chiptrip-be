package com.tranbac.chiptripbe.module.chat.entity;

import com.tranbac.chiptripbe.common.entity.BaseAuditEntity;
import com.tranbac.chiptripbe.module.chat.enums.MessageType;
import com.tranbac.chiptripbe.module.chat.enums.SenderRole;
import com.tranbac.chiptripbe.module.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Nationalized;

@Entity
@Table(name = "chat_messages",
        indexes = {
                @Index(name = "ix_chat_messages_conv_id", columnList = "conversation_id, id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessage extends BaseAuditEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_chat_messages_conversation"))
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_chat_messages_sender"))
    private User sender;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_role", nullable = false, length = 10)
    private SenderRole senderRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 10)
    private MessageType messageType;

    /** Null khi messageType = IMAGE. */
    @Nationalized
    @Column(name = "content", length = 2000)
    private String content;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    /** R2 object key — giữ riêng để có thể xóa/cleanup sau này. */
    @Column(name = "image_key", length = 300)
    private String imageKey;
}
