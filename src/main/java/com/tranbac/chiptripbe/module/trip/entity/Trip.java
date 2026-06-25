package com.tranbac.chiptripbe.module.trip.entity;

import com.tranbac.chiptripbe.common.entity.BaseAuditEntity;
import com.tranbac.chiptripbe.module.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Nationalized;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "trips",
        indexes = {
                @Index(name = "ix_trips_user", columnList = "user_id"),
                @Index(name = "ix_trips_share_token", columnList = "share_token", unique = true),
                @Index(name = "ix_trips_invite_token", columnList = "invite_token", unique = true),
                @Index(name = "ix_trips_public_published", columnList = "is_public, published_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Trip extends BaseAuditEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_trips_user"))
    private User user;

    @Nationalized
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Nationalized
    @Column(name = "departure", nullable = false, length = 150)
    private String departure;

    @Nationalized
    @Column(name = "destination", nullable = false, length = 150)
    private String destination;

    @Column(name = "date_start", nullable = false)
    private LocalDate dateStart;

    @Column(name = "date_end", nullable = false)
    private LocalDate dateEnd;

    @Column(name = "people_count", nullable = false)
    private Integer peopleCount;

    @Column(name = "budget_vnd", nullable = false)
    private Long budgetVnd;

    @Column(name = "styles", length = 500)
    private String styles;

    @Column(name = "share_token", length = 64)
    private String shareToken;

    /** Token link mời thành viên — pattern như shareToken; null = chưa bật/đã thu hồi. */
    @Column(name = "invite_token", length = 64)
    private String inviteToken;

    /** Trip công khai hiển thị ở feed Khám phá. */
    @Column(name = "is_public", columnDefinition = "bit NOT NULL DEFAULT 0")
    @Builder.Default
    private boolean isPublic = false;

    /** Lần đầu publish — giữ nguyên khi unpublish/republish để feed ổn định thứ tự. */
    @Column(name = "published_at", columnDefinition = "DATETIME2")
    private LocalDateTime publishedAt;

    /** Denormalized counter — sync từ trip_likes qua TripRepository.updateLikesCount. */
    @Column(name = "likes_count", columnDefinition = "INT NOT NULL DEFAULT 0")
    @Builder.Default
    private Integer likesCount = 0;

    /** Denormalized counter — sync từ trip_comments qua TripRepository.updateCommentsCount. */
    @Column(name = "comments_count", columnDefinition = "INT NOT NULL DEFAULT 0")
    @Builder.Default
    private Integer commentsCount = 0;

    @Column(name = "generated_as_premium", columnDefinition = "bit NOT NULL DEFAULT 0")
    @Builder.Default
    private boolean generatedAsPremium = false;

    @Column(name = "activity_swap_free_limit", columnDefinition = "INT NOT NULL DEFAULT 0")
    @Builder.Default
    private Integer activitySwapFreeLimit = 0;

    @Column(name = "activity_swap_free_used", columnDefinition = "INT NOT NULL DEFAULT 0")
    @Builder.Default
    private Integer activitySwapFreeUsed = 0;

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("dayNumber ASC")
    @Builder.Default
    private List<TripDay> days = new ArrayList<>();

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC")
    @Builder.Default
    private List<ChecklistItem> checklist = new ArrayList<>();
    // THÊM ĐOẠN NÀY
    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<TripMember> members = new ArrayList<>();

    public void addDay(TripDay day) {
        days.add(day);
        day.setTrip(this);
    }
    public void addMember(TripMember member) {
        members.add(member);
        member.setTrip(this);
    }

    public void removeMember(TripMember member) {
        members.remove(member);
        member.setTrip(null);
    }

    public void addChecklistItem(ChecklistItem item) {
        checklist.add(item);
        item.setTrip(this);
    }
}
