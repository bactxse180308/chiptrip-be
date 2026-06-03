package com.tranbac.chiptripbe.common.service.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;
    private final MailProperties mailProperties;

    @Async
    @Override
    public void sendVerificationEmail(String toEmail, String fullName, String token) {
        String link = mailProperties.getVerificationUrl() + "?token=" + token;
        String subject = "Xac nhan dia chi email - ChipTrip";
        String html = buildVerificationHtml(fullName, link, mailProperties.getVerificationExpiryHours());
        sendHtml(toEmail, subject, html);
        log.info("Verification email sent to [REDACTED]");
    }

    @Async
    @Override
    public void sendPasswordResetEmail(String toEmail, String fullName, String token) {
        String link = mailProperties.getResetPasswordUrl() + "?token=" + token;
        String subject = "Dat lai mat khau - ChipTrip";
        String html = buildPasswordResetHtml(fullName, link, mailProperties.getResetPasswordExpiryHours());
        sendHtml(toEmail, subject, html);
        log.info("Password reset email sent to [REDACTED]");
    }

    @Async
    @Override
    public void sendOtpEmail(String toEmail, String fullName, String otp, int expiryMinutes, String purpose) {
        String subject = "Ma xac nhan ChipTrip";
        String html = buildOtpHtml(fullName, otp, expiryMinutes, purpose);
        sendHtml(toEmail, subject, html);
        log.info("OTP email sent to [REDACTED] for purpose: {}", purpose);
    }

    private void sendHtml(String to, String subject, String htmlContent) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailProperties.getFrom());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);
            mailSender.send(message);
        } catch (MessagingException e) {
            log.error("Failed to send email to [REDACTED]: {}", e.getMessage());
        }
    }

    private String buildVerificationHtml(String fullName, String link, int expiryHours) {
        return """
                <!DOCTYPE html>
                <html lang="vi">
                <head><meta charset="UTF-8"></head>
                <body style="font-family:Arial,sans-serif;background:#f4f4f4;margin:0;padding:20px;">
                  <div style="max-width:600px;margin:0 auto;background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,.1);">
                    <div style="background:#4F46E5;padding:32px 24px;text-align:center;">
                      <h1 style="color:#fff;margin:0;font-size:24px;">ChipTrip</h1>
                    </div>
                    <div style="padding:32px 24px;">
                      <h2 style="color:#1f2937;margin-top:0;">Xac nhan dia chi email</h2>
                      <p style="color:#4b5563;">Xin chao <strong>%s</strong>,</p>
                      <p style="color:#4b5563;">Cam on ban da dang ky ChipTrip! Vui long nhan nut ben duoi de xac nhan dia chi email cua ban.</p>
                      <div style="text-align:center;margin:32px 0;">
                        <a href="%s" style="background:#4F46E5;color:#fff;padding:14px 32px;border-radius:6px;text-decoration:none;font-weight:bold;display:inline-block;">
                          Xac nhan email
                        </a>
                      </div>
                      <p style="color:#6b7280;font-size:14px;">Lien ket nay se het han sau <strong>%d gio</strong>.</p>
                      <p style="color:#6b7280;font-size:14px;">Neu ban khong dang ky tai khoan, hay bo qua email nay.</p>
                      <hr style="border:none;border-top:1px solid #e5e7eb;margin:24px 0;">
                      <p style="color:#9ca3af;font-size:12px;text-align:center;">© 2025 ChipTrip. Tat ca quyen duoc bao luu.</p>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(fullName, link, expiryHours);
    }

    private String buildPasswordResetHtml(String fullName, String link, int expiryHours) {
        return """
                <!DOCTYPE html>
                <html lang="vi">
                <head><meta charset="UTF-8"></head>
                <body style="font-family:Arial,sans-serif;background:#f4f4f4;margin:0;padding:20px;">
                  <div style="max-width:600px;margin:0 auto;background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,.1);">
                    <div style="background:#4F46E5;padding:32px 24px;text-align:center;">
                      <h1 style="color:#fff;margin:0;font-size:24px;">ChipTrip</h1>
                    </div>
                    <div style="padding:32px 24px;">
                      <h2 style="color:#1f2937;margin-top:0;">Dat lai mat khau</h2>
                      <p style="color:#4b5563;">Xin chao <strong>%s</strong>,</p>
                      <p style="color:#4b5563;">Chung toi nhan duoc yeu cau dat lai mat khau cho tai khoan cua ban. Nhan nut ben duoi de tiep tuc.</p>
                      <div style="text-align:center;margin:32px 0;">
                        <a href="%s" style="background:#4F46E5;color:#fff;padding:14px 32px;border-radius:6px;text-decoration:none;font-weight:bold;display:inline-block;">
                          Dat lai mat khau
                        </a>
                      </div>
                      <p style="color:#6b7280;font-size:14px;">Lien ket nay se het han sau <strong>%d gio</strong>.</p>
                      <p style="color:#6b7280;font-size:14px;">Neu ban khong yeu cau dat lai mat khau, hay bo qua email nay. Mat khau cua ban se khong thay doi.</p>
                      <hr style="border:none;border-top:1px solid #e5e7eb;margin:24px 0;">
                      <p style="color:#9ca3af;font-size:12px;text-align:center;">© 2025 ChipTrip. Tat ca quyen duoc bao luu.</p>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(fullName, link, expiryHours);
    }

    private String buildOtpHtml(String fullName, String otp, int expiryMinutes, String purpose) {
        String purposeText = "EMAIL_VERIFICATION".equals(purpose) ? "xac nhan email" : "dat lai mat khau";
        return """
                <!DOCTYPE html>
                <html lang="vi">
                <head><meta charset="UTF-8"></head>
                <body style="font-family:Arial,sans-serif;background:#f4f4f4;margin:0;padding:20px;">
                  <div style="max-width:600px;margin:0 auto;background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,.1);">
                    <div style="background:#4F46E5;padding:32px 24px;text-align:center;">
                      <h1 style="color:#fff;margin:0;font-size:24px;">ChipTrip</h1>
                    </div>
                    <div style="padding:32px 24px;text-align:center;">
                      <h2 style="color:#1f2937;margin-top:0;">Ma xac nhan</h2>
                      <p style="color:#4b5563;">Xin chao <strong>%s</strong>,</p>
                      <p style="color:#4b5563;">Ma xac nhan cua ban de %s:</p>
                      <div style="background:#f3f4f6;border-radius:8px;padding:24px;margin:24px 0;font-size:36px;font-weight:bold;letter-spacing:8px;color:#4F46E5;text-align:center;">
                        %s
                      </div>
                      <p style="color:#6b7280;font-size:14px;">Ma nay co hieu luc trong <strong>%d phut</strong>.</p>
                      <p style="color:#6b7280;font-size:14px;">Neu ban khong yeu cau ma nay, hay bo qua email.</p>
                      <hr style="border:none;border-top:1px solid #e5e7eb;margin:24px 0;">
                      <p style="color:#9ca3af;font-size:12px;text-align:center;">© 2025 ChipTrip. Tat ca quyen duoc bao luu.</p>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(fullName, purposeText, otp, expiryMinutes);
    }
}
