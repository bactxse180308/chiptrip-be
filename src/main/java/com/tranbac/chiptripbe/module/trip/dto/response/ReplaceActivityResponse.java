package com.tranbac.chiptripbe.module.trip.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class ReplaceActivityResponse {
    private TripDetailResponse.ActivityDetail activity;
    private Integer freeSwapsRemaining;
    private Integer chargedUnits;
    private BigDecimal chargedCredits;
    private Integer aiCreditUnitsRemaining;
    private BigDecimal aiCreditBalance;
}
