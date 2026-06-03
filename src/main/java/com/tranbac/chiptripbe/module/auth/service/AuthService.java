package com.tranbac.chiptripbe.module.auth.service;

import com.tranbac.chiptripbe.module.auth.dto.request.ForgotPasswordRequest;
import com.tranbac.chiptripbe.module.auth.dto.request.LoginRequest;
import com.tranbac.chiptripbe.module.auth.dto.request.RefreshTokenRequest;
import com.tranbac.chiptripbe.module.auth.dto.request.RegisterRequest;
import com.tranbac.chiptripbe.module.auth.dto.request.ResetPasswordRequest;
import com.tranbac.chiptripbe.module.auth.dto.request.ResetPasswordWithOtpRequest;
import com.tranbac.chiptripbe.module.auth.dto.request.SendOtpRequest;
import com.tranbac.chiptripbe.module.auth.dto.request.VerifyOtpRequest;
import com.tranbac.chiptripbe.module.auth.dto.response.AuthResponse;

public interface AuthService {

    void register(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    AuthResponse refresh(RefreshTokenRequest request);

    void logout(Long userId);

    void sendOtp(SendOtpRequest request);

    void verifyOtp(VerifyOtpRequest request);

    void resetPasswordWithOtp(ResetPasswordWithOtpRequest request);

    void forgotPassword(ForgotPasswordRequest request);

    void resetPassword(ResetPasswordRequest request);

    AuthResponse googleLogin(String idToken);
}