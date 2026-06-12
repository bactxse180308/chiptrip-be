package com.tranbac.chiptripbe.module.payment.controller;

import com.tranbac.chiptripbe.module.payment.config.SepayProperties;
import com.tranbac.chiptripbe.module.payment.dto.SepayWebhookPayload;
import com.tranbac.chiptripbe.module.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@Tag(name = "Webhook", description = "SePay payment webhook receiver")
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class SepayWebhookController {

    private final PaymentService paymentService;
    private final SepayProperties sepayProperties;

    @Operation(summary = "Receive SePay transaction webhook")
    @PostMapping("/sepay")
    public ResponseEntity<Map<String, Boolean>> handleSepayWebhook(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody SepayWebhookPayload payload) {

        if (!verifyApiKey(authHeader)) {
            log.warn("Invalid API key from SePay webhook, sepayId={}", payload.getId());
            return ResponseEntity.status(401).body(Map.of("success", false));
        }

        log.info("SePay webhook accepted: id={}, transferType={}, amount={}, content='{}', code='{}', description='{}', referenceCode='{}'",
                payload.getId(), payload.getTransferType(), payload.getTransferAmount(),
                payload.getContent(), payload.getCode(), payload.getDescription(), payload.getReferenceCode());
        paymentService.processWebhook(payload);
        return ResponseEntity.ok(Map.of("success", true));
    }

    private boolean verifyApiKey(String authHeader) {
        String expectedKey = sepayProperties.getApiKey();
        if (expectedKey == null || expectedKey.isBlank()) {
            log.warn("SePay API key not configured — rejecting webhook");
            return false;
        }
        if (authHeader == null || !authHeader.startsWith("Apikey ")) {
            return false;
        }
        String providedKey = authHeader.substring("Apikey ".length()).trim();
        return expectedKey.equals(providedKey);
    }
}
