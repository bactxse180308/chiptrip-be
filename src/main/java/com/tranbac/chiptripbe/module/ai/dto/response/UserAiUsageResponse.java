package com.tranbac.chiptripbe.module.ai.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class UserAiUsageResponse {

    private List<AiUsageDetail> usages;
    private LongSummary summary;

    @Getter
    @Builder
    public static class AiUsageDetail {
        private Long id;
        private Long tripId;
        private String tripTitle;
        private String provider;
        private Integer tokensIn;
        private Integer tokensOut;
        private BigDecimal costUsd;
        private LocalDateTime createdAt;
    }

    @Getter
    @Builder
    public static class LongSummary {
        private Long totalCount;
        private Long totalTokensIn;
        private Long totalTokensOut;
        private BigDecimal totalCostUsd;
    }
}
