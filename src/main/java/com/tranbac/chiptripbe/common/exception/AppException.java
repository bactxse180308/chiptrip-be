package com.tranbac.chiptripbe.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class AppException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Object details;

    public AppException(HttpStatus status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
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
}