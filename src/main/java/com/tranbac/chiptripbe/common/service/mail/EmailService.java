package com.tranbac.chiptripbe.common.service.mail;

public interface EmailService {
    void sendVerificationEmail(String toEmail, String fullName, String token);
    void sendPasswordResetEmail(String toEmail, String fullName, String token);
    void sendOtpEmail(String toEmail, String fullName, String otp, int expiryMinutes, String purpose);
}
