package com.tranbac.chiptripbe.module.trip.entity;

import com.tranbac.chiptripbe.common.entity.BaseAuditEntity;
import com.tranbac.chiptripbe.module.trip.enums.ActivityAlternativeCategory;
import com.tranbac.chiptripbe.module.trip.enums.ActivityAlternativeSessionStatus;
import com.tranbac.chiptripbe.module.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "activity_alternative_sessions",
        indexes = {
                @Index(name = "ix_activity_alt_session_trip", columnList = "trip_id"),
                @Index(name = "ix_activity_alt_session_activity", columnList = "activity_id"),
                @Index(name = "ix_activity_alt_session_user", columnList = "user_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActivityAlternativeSession extends BaseAuditEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_activity_alt_sessions_user"))
    private User user;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false, insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_activity_alt_sessions_trip"))
    private Trip trip;

    @Column(name = "day_id", nullable = false)
    private Long dayId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "day_id", nullable = false, insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_activity_alt_sessions_day"))
    private TripDay day;

    @Column(name = "activity_id", nullable = false)
    private Long activityId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_id", nullable = false, insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_activity_alt_sessions_activity"))
    private Activity activity;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private ActivityAlternativeCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ActivityAlternativeSessionStatus status = ActivityAlternativeSessionStatus.PENDING;

    @Column(name = "options_json", nullable = false, columnDefinition = "NVARCHAR(MAX)")
    private String optionsJson;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "expires_at", nullable = false, columnDefinition = "DATETIME2")
    private LocalDateTime expiresAt;
}
