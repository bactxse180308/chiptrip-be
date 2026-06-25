package com.tranbac.chiptripbe.module.trip.dto.request;

import com.tranbac.chiptripbe.module.trip.enums.ActivityAlternativeCategory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ActivityAlternativesRequest {

    @NotNull
    private ActivityAlternativeCategory category;

    @Min(1)
    @Max(4)
    private Integer limit = 4;
}
