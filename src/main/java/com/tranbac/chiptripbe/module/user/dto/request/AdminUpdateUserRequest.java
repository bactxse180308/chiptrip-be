package com.tranbac.chiptripbe.module.user.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AdminUpdateUserRequest {

    @Size(max = 150)
    private String fullName;

    @Min(0)
    private Integer aiCredits;
}
