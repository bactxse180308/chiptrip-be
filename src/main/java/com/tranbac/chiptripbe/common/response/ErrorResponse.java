package com.tranbac.chiptripbe.common.response;

import java.time.Instant;

public record ErrorResponse(
        boolean success,
        String code,
        String message,
        Object details,
        Instant timestamp
) {
    public static ErrorResponse of(String code, String message, Object details) {
        return new ErrorResponse(false, code, message, details, Instant.now());
    }
}