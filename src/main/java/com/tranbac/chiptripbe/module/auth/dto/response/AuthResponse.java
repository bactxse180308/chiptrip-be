package com.tranbac.chiptripbe.module.auth.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AuthResponse {
    private String accessToken;
    private String refreshToken;
    private Long expiresIn;
    private String tokenType;
    private Long userId;
    private String email;
    private String fullName;
}
