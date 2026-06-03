package com.tranbac.chiptripbe.module.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class GoogleLoginRequest {
    @NotBlank(message = "ID token is required")
    private String idToken;
}
