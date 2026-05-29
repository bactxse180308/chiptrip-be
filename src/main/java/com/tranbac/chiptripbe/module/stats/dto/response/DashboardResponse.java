package com.tranbac.chiptripbe.module.stats.dto.response;

import lombok.Builder;
import lombok.Getter;
import java.math.BigDecimal;

@Getter
@Builder
public class DashboardResponse {
    private long totalUsers;
    private long totalTrips;
    private long aiCallsThisMonth;
    private BigDecimal aiCostUsdThisMonth;
}
