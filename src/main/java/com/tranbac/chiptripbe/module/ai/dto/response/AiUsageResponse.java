package com.tranbac.chiptripbe.module.ai.dto.response;

import lombok.Builder;
import lombok.Getter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class AiUsageResponse {
    private Long id;
    private String provider;
    private Integer tokensIn;
    private Integer tokensOut;
    private BigDecimal costUsd;
    private LocalDateTime createdAt;

    private Long userId;
    private String userEmail;
    private String userFullName;

    private Long tripId;
    private String tripTitle;
}
