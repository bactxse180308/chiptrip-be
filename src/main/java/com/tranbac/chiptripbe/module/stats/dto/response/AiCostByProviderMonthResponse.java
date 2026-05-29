package com.tranbac.chiptripbe.module.stats.dto.response;

import lombok.Builder;
import lombok.Getter;
import java.math.BigDecimal;

@Getter
@Builder
public class AiCostByProviderMonthResponse {
    private String provider;
    private String month;
    private long usageCount;
    private long totalTokensIn;
    private long totalTokensOut;
    private BigDecimal totalCostUsd;
}
