package com.tranbac.chiptripbe.module.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class ForgotPasswordRequest {

    @NotBlank
    @Email
    private String email;
}
