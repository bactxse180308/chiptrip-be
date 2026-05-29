package com.tranbac.chiptripbe.module.user.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class AdminUserDetailResponse {

    // ── User core ──────────────────────────────────────────────────────────────
    private Long id;
    private String email;
    private String fullName;
    private String avatarUrl;
    private Integer aiCredits;
    private Boolean isActive;
    private Boolean emailVerified;
    private String role;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ── Related data ───────────────────────────────────────────────────────────
    private List<TripSummary> trips;
    private AiUsageSummary aiUsage;
    private long activeSessionCount;

    @Getter
    @Builder
    public static class TripSummary {
        private Long id;
        private String title;
        private String departure;
        private String destination;
        private LocalDate dateStart;
        private LocalDate dateEnd;
        private Integer peopleCount;
        private Long budgetVnd;
        private String styles;
        private LocalDateTime createdAt;
    }

    @Getter
    @Builder
    public static class AiUsageSummary {
        private long totalCount;
        private long totalTokensIn;
        private long totalTokensOut;
        private BigDecimal totalCostUsd;
    }
}
