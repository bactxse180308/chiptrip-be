package com.tranbac.chiptripbe.common.response;

import java.time.Instant;
import java.util.Map;

public record ErrorResponse(
        boolean success,
        String code,
        String message,
        Object details,
        Map<String, Object> meta,
        Instant timestamp
) {
    public static ErrorResponse of(String code, String message, Object details) {
        return new ErrorResponse(false, code, message, details, null, Instant.now());
    }

    public static ErrorResponse of(String code, String message, Object details, Map<String, Object> meta) {
        return new ErrorResponse(false, code, message, details, meta, Instant.now());
    }
}