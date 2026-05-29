package com.tranbac.chiptripbe.module.stats.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DailyCountResponse {
    private String date;
    private long count;
}
