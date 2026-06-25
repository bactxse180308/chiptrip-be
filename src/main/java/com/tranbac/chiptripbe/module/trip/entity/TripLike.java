package com.tranbac.chiptripbe.module.trip.entity;

import com.tranbac.chiptripbe.common.entity.BaseEntity;
import com.tranbac.chiptripbe.module.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "trip_likes",
        uniqueConstraints = @UniqueConstraint(name = "uk_trip_likes_trip_user", columnNames = {"trip_id", "user_id"}),
        indexes = {
                @Index(name = "ix_trip_likes_trip", columnList = "trip_id"),
                @Index(name = "ix_trip_likes_user", columnList = "user_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripLike extends BaseEntity {

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false, insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_trip_likes_trip"))
    private Trip trip;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, insertable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_trip_likes_user"))
    private User user;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME2")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
