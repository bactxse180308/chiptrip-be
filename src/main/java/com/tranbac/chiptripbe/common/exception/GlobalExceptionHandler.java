package com.tranbac.chiptripbe.common.exception;

import com.tranbac.chiptripbe.common.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Client disconnected before response was sent — not a server error
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientAbort(AsyncRequestNotUsableException ex, HttpServletRequest req) {
        log.debug("Client disconnected [{} {}]: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handleApp(HttpServletRequest req, AppException ex) {
        String path = req.getMethod() + " " + req.getRequestURI();
        if (ex.getStatus().is5xxServerError()) {
            log.error("[{}] {} → {}: {}", ex.getStatus().value(), path, ex.getCode(), ex.getMessage());
        } else {
            log.warn("[{}] {} → {}: {}", ex.getStatus().value(), path, ex.getCode(), ex.getMessage());
        }
        return ResponseEntity.status(ex.getStatus())
                .body(ErrorResponse.of(ex.getCode(), ex.getMessage(), ex.getDetails(), ex.getMeta()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(HttpServletRequest req, MethodArgumentNotValidException ex) {
        Map<String, String> details = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage, (a, b) -> a));
        log.warn("[400] {} {} → VALIDATION_FAILED: {}", req.getMethod(), req.getRequestURI(), details);
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("VALIDATION_FAILED", "Validation error", details));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(HttpServletRequest req, Exception ex) {
        log.error("Unhandled exception [{} {}]", req.getMethod(), req.getRequestURI(), ex);
        return ResponseEntity.internalServerError()
                .body(ErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred", null));
    }
}