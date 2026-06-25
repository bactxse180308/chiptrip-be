package com.tranbac.chiptripbe.module.moderation.entity;

import com.tranbac.chiptripbe.common.entity.BaseEntity;
import com.tranbac.chiptripbe.module.moderation.enums.ReportStatus;
import com.tranbac.chiptripbe.module.moderation.enums.ReportTargetType;
import com.tranbac.chiptripbe.module.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Nationalized;
import java.time.LocalDateTime;

/** Báo cáo nội dung vi phạm (comment hoặc trip public) do user gửi, admin xử lý. */
@Entity
@Table(name = "content_reports",
        indexes = {
                @Index(name = "ix_content_reports_status", columnList = "status, created_at"),
                @Index(name = "ix_content_reports_target", columnList = "target_type, target_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentReport extends BaseEntity {

    @Column(name = "reporter_user_id", nullable = false)
    private Long reporterUserId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_user_id", nullable = false, insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_content_reports_reporter"))
    private User reporter;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private ReportTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    // sensitive — do not log (lý do user nhập tự do)
    @Nationalized
    @Column(name = "reason", length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ReportStatus status = ReportStatus.PENDING;

    @Column(name = "resolved_by_admin_id")
    private Long resolvedByAdminId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by_admin_id", insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_content_reports_resolved_by_admin"))
    private User resolvedByAdmin;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME2")
    private LocalDateTime createdAt;

    @Column(name = "resolved_at", columnDefinition = "DATETIME2")
    private LocalDateTime resolvedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
