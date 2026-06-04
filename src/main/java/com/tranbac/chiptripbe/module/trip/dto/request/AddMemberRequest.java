package com.tranbac.chiptripbe.module.trip.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class AddMemberRequest {

    @Min(1)
    private Long userId; // null → guest member

    @Size(max = 150)
    private String displayName; // required when userId is null; optional override when userId set
}
