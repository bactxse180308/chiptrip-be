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
    private Integer aiCreditUnits;
    private BigDecimal aiCreditBalance;
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
    private PaymentSummary payment;

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

    @Getter
    @Builder
    public static class PaymentSummary {
        /** true nếu user có ≥1 đơn đã thanh toán (PAID). */
        private boolean premium;
        private long paidOrderCount;
        private long totalSpentVnd;
        private long totalCreditsPurchased;
        private LocalDateTime lastPaidAt;
        private List<OrderItem> orders;
    }

    @Getter
    @Builder
    public static class OrderItem {
        private Long id;
        private String orderCode;
        private String planCode;
        private Long amountVnd;
        private Integer credits;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime paidAt;
    }
}
