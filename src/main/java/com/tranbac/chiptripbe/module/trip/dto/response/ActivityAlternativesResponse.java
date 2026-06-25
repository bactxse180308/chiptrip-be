package com.tranbac.chiptripbe.module.trip.dto.response;

import com.tranbac.chiptripbe.module.trip.enums.ActivityAlternativeCategory;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Builder
public class ActivityAlternativesResponse {
    private Long sessionId;
    private ActivityAlternativeCategory category;
    private Integer freeSwapsRemaining;
    private Integer chargeUnitsIfApplied;
    private BigDecimal chargeCreditsIfApplied;
    private List<ActivityAlternativeOptionResponse> options;
}
