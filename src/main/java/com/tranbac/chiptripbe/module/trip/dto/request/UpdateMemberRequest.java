package com.tranbac.chiptripbe.module.trip.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class UpdateMemberRequest {

    @NotBlank
    @Size(max = 150)
    private String displayName;
}
