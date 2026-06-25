package com.tranbac.chiptripbe.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Map;

@Getter
public class AppException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Object details;
    /** Ngữ cảnh máy đọc cho FE (vd requiredAccountType, canBuyCredits) — map sang popup nạp gói. */
    private final Map<String, Object> meta;

    public AppException(HttpStatus status, String code, String message, Object details) {
        this(status, code, message, details, null);
    }

    public AppException(HttpStatus status, String code, String message, Object details, Map<String, Object> meta) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
        this.meta = meta;
    }

    public static AppException notFound(String message) {
        return new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", message, null);
    }

    public static AppException badRequest(String message) {
        return new AppException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message, null);
    }

    public static AppException conflict(String message) {
        return new AppException(HttpStatus.CONFLICT, "CONFLICT", message, null);
    }

    public static AppException forbidden(String message) {
        return new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", message, null);
    }

    public static AppException unauthorized(String message) {
        return new AppException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message, null);
    }

    public static AppException tooManyRequests(String code, String message) {
        return new AppException(HttpStatus.TOO_MANY_REQUESTS, code, message, null);
    }

    public static AppException internal(String message) {
        return new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", message, null);
    }

    public static AppException emailNotVerified() {
        return new AppException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED",
                "Email chưa được xác nhận. Vui lòng kiểm tra hộp thư và nhập mã OTP.", null);
    }

    // ─── Credit & Premium (xem CREDIT_PREMIUM_SPEC.md Mục 4) ──────────────────

    /** 403 — Normal bấm tính năng chỉ dành cho Premium (export PDF, đổi hoạt động). */
    public static AppException premiumRequired() {
        return new AppException(HttpStatus.FORBIDDEN, "PREMIUM_REQUIRED",
                "Tính năng này dành cho tài khoản Premium. Vui lòng nạp gói để sử dụng.", null,
                Map.of("requiredAccountType", "PREMIUM", "canBuyCredits", true));
    }

    /** 402 — Normal, hết paid và hết lượt trial hôm nay. */
    public static AppException dailyTrialUsed() {
        return new AppException(HttpStatus.PAYMENT_REQUIRED, "DAILY_TRIAL_USED",
                "Bạn đã dùng hết lượt miễn phí hôm nay. Vui lòng nạp gói hoặc quay lại vào ngày mai.", null,
                Map.of("canBuyCredits", true));
    }

    /** 402 — Cần paid credit (vd đổi hoạt động 0.25) nhưng không đủ. */
    public static AppException insufficientPaid() {
        return new AppException(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_PAID_CREDITS",
                "Bạn không đủ credit. Vui lòng nạp thêm để tiếp tục.", null,
                Map.of("canBuyCredits", true));
    }

    /** 403 — Vượt giới hạn theo tier (vd days>3 hoặc styles>5 với Normal). */
    public static AppException limitExceeded(String message, String requiredAccountType) {
        return new AppException(HttpStatus.FORBIDDEN, "LIMIT_EXCEEDED", message, null,
                Map.of("requiredAccountType", requiredAccountType, "canBuyCredits", true));
    }
}