package com.tranbac.chiptripbe.module.user.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class UserResponse {
    private Long id;
    private String email;
    private String fullName;
    private String avatarUrl;
    private Integer aiCredits;
    private Boolean isActive;
    private Boolean emailVerified;
    private String role;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}