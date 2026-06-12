package com.tranbac.chiptripbe.module.stats.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class EventCountResponse {
    private String event;
    private long count;
}
