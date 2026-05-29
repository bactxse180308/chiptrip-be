package com.tranbac.chiptripbe.common.mail;

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
        String subject = "Xác nhận địa chỉ email - ChipTrip";
        String html = buildVerificationHtml(fullName, link, mailProperties.getVerificationExpiryHours());
        sendHtml(toEmail, subject, html);
        log.info("Verification email sent to [REDACTED]");
    }

    @Async
    @Override
    public void sendPasswordResetEmail(String toEmail, String fullName, String token) {
        String link = mailProperties.getResetPasswordUrl() + "?token=" + token;
        String subject = "Đặt lại mật khẩu - ChipTrip";
        String html = buildPasswordResetHtml(fullName, link, mailProperties.getResetPasswordExpiryHours());
        sendHtml(toEmail, subject, html);
        log.info("Password reset email sent to [REDACTED]");
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
                      <h2 style="color:#1f2937;margin-top:0;">Xác nhận địa chỉ email</h2>
                      <p style="color:#4b5563;">Xin chào <strong>%s</strong>,</p>
                      <p style="color:#4b5563;">Cảm ơn bạn đã đăng ký ChipTrip! Vui lòng nhấn nút bên dưới để xác nhận địa chỉ email của bạn.</p>
                      <div style="text-align:center;margin:32px 0;">
                        <a href="%s" style="background:#4F46E5;color:#fff;padding:14px 32px;border-radius:6px;text-decoration:none;font-weight:bold;display:inline-block;">
                          Xác nhận email
                        </a>
                      </div>
                      <p style="color:#6b7280;font-size:14px;">Liên kết này sẽ hết hạn sau <strong>%d giờ</strong>.</p>
                      <p style="color:#6b7280;font-size:14px;">Nếu bạn không đăng ký tài khoản, hãy bỏ qua email này.</p>
                      <hr style="border:none;border-top:1px solid #e5e7eb;margin:24px 0;">
                      <p style="color:#9ca3af;font-size:12px;text-align:center;">© 2025 ChipTrip. Tất cả quyền được bảo lưu.</p>
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
                      <h2 style="color:#1f2937;margin-top:0;">Đặt lại mật khẩu</h2>
                      <p style="color:#4b5563;">Xin chào <strong>%s</strong>,</p>
                      <p style="color:#4b5563;">Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản của bạn. Nhấn nút bên dưới để tiếp tục.</p>
                      <div style="text-align:center;margin:32px 0;">
                        <a href="%s" style="background:#4F46E5;color:#fff;padding:14px 32px;border-radius:6px;text-decoration:none;font-weight:bold;display:inline-block;">
                          Đặt lại mật khẩu
                        </a>
                      </div>
                      <p style="color:#6b7280;font-size:14px;">Liên kết này sẽ hết hạn sau <strong>%d giờ</strong>.</p>
                      <p style="color:#6b7280;font-size:14px;">Nếu bạn không yêu cầu đặt lại mật khẩu, hãy bỏ qua email này. Mật khẩu của bạn sẽ không thay đổi.</p>
                      <hr style="border:none;border-top:1px solid #e5e7eb;margin:24px 0;">
                      <p style="color:#9ca3af;font-size:12px;text-align:center;">© 2025 ChipTrip. Tất cả quyền được bảo lưu.</p>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(fullName, link, expiryHours);
    }
}
