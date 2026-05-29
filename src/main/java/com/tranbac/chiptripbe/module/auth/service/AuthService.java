package com.tranbac.chiptripbe.module.auth.service;

import com.tranbac.chiptripbe.module.auth.dto.request.ForgotPasswordRequest;
import com.tranbac.chiptripbe.module.auth.dto.request.LoginRequest;
import com.tranbac.chiptripbe.module.auth.dto.request.RefreshTokenRequest;
import com.tranbac.chiptripbe.module.auth.dto.request.RegisterRequest;
import com.tranbac.chiptripbe.module.auth.dto.request.ResetPasswordRequest;
import com.tranbac.chiptripbe.module.auth.dto.response.AuthResponse;

public interface AuthService {

    void register(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    AuthResponse refresh(RefreshTokenRequest request);

    void logout(Long userId);

    void verifyEmail(String token);

    void forgotPassword(ForgotPasswordRequest request);

    void resetPassword(ResetPasswordRequest request);

    AuthResponse googleLogin(String idToken);
}