package com.tranbac.chiptripbe.module.notification.entity;

import com.tranbac.chiptripbe.common.entity.BaseAuditEntity;
import com.tranbac.chiptripbe.module.notification.enums.NotificationType;
import com.tranbac.chiptripbe.module.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Nationalized;

@Entity
@Table(name = "notifications",
        indexes = {
                @Index(name = "ix_notifications_user_unread_created",
                        columnList = "user_id, is_read, created_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification extends BaseAuditEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_notifications_user"))
    private User recipient;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    private NotificationType type;

    @Nationalized
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Nationalized
    @Column(name = "body", length = 500)
    private String body;

    /** Id polymorphic của object liên quan (vd tripId). Không dùng FK cứng vì có nhiều loại. */
    @Column(name = "ref_id")
    private Long refId;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private boolean isRead = false;
}
