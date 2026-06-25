package com.tranbac.chiptripbe.module.user.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class UserResponse {
    private Long id;
    private String email;
    private String fullName;
    private String avatarUrl;
    private Integer aiCredits;
    private Integer aiCreditUnits;
    private BigDecimal aiCreditBalance;
    /** Premium suy ra từ paid balance (paidCreditBalance > 0) — thay cho check role cũ. */
    private Boolean isPremium;
    /** Lượt miễn phí còn hôm nay (0/1). */
    private Integer trialCreditBalance;
    private Boolean isActive;
    private Boolean emailVerified;
    private String role;
    private String preferences;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
