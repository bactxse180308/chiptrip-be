package com.tranbac.chiptripbe.module.trip.entity;

import com.tranbac.chiptripbe.common.entity.BaseAuditEntity;
import com.tranbac.chiptripbe.module.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Nationalized;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "trips",
        indexes = {
                @Index(name = "ix_trips_user", columnList = "user_id"),
                @Index(name = "ix_trips_share_token", columnList = "share_token", unique = true)
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

    public void addDay(TripDay day) {
        days.add(day);
        day.setTrip(this);
    }

    public void addChecklistItem(ChecklistItem item) {
        checklist.add(item);
        item.setTrip(this);
    }
}