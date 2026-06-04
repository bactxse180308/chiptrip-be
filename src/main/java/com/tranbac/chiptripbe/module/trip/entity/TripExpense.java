package com.tranbac.chiptripbe.module.trip.entity;

import com.tranbac.chiptripbe.common.entity.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Nationalized;

@Entity
@Table(name = "trip_expenses",
        indexes = {
                @Index(name = "ix_trip_expenses_trip", columnList = "trip_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripExpense extends BaseAuditEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false, foreignKey = @ForeignKey(name = "fk_trip_expenses_trip"))
    private Trip trip;

    @Column(name = "paid_by", nullable = false, length = 100)
    private String paidBy;

    @Nationalized
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "category", length = 50)
    private String category;

    @Column(name = "split_among", length = 1000)
    private String splitAmong;
}
