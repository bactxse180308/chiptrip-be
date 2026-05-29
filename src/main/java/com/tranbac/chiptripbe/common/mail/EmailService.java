package com.tranbac.chiptripbe.common.mail;

public interface EmailService {
    void sendVerificationEmail(String toEmail, String fullName, String token);
    void sendPasswordResetEmail(String toEmail, String fullName, String token);
}
