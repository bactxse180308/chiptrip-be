package com.tranbac.chiptripbe.module.user.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class UpdateProfileRequest {

    @Size(max = 150)
    private String fullName;

    @Size(max = 500)
    private String avatarUrl;

    @Size(max = 500)
    private String preferences;
}