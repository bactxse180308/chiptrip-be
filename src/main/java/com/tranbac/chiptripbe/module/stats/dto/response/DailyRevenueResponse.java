package com.tranbac.chiptripbe.module.stats.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DailyRevenueResponse {
    private String date;
    private long orders;
    private long revenueVnd;
}
