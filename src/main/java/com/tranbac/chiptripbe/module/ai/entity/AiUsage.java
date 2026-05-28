package com.tranbac.chiptripbe.module.ai.entity;

import com.tranbac.chiptripbe.common.entity.BaseEntity;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_usages",
        indexes = {
                @Index(name = "ix_ai_usages_user", columnList = "user_id"),
                @Index(name = "ix_ai_usages_created", columnList = "created_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiUsage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_ai_usages_user"))
    private User user;

    // trip_id set null khi trip bị xoá để giữ audit log
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id",
            foreignKey = @ForeignKey(name = "fk_ai_usages_trip"))
    private Trip trip;

    @Column(name = "provider", nullable = false, length = 30)
    private String provider;

    @Column(name = "tokens_in", nullable = false)
    private Integer tokensIn;

    @Column(name = "tokens_out", nullable = false)
    private Integer tokensOut;

    @Column(name = "cost_usd", nullable = false, precision = 10, scale = 6)
    private BigDecimal costUsd;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "DATETIME2")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}