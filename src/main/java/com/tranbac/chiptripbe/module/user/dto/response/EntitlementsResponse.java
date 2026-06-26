package com.tranbac.chiptripbe.module.user.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/** Trả cho FE để gate UI (CREDIT_PREMIUM_SPEC.md Mục 5.4). */
@Getter
@Builder
public class EntitlementsResponse {

    /** "NORMAL" hoặc "PREMIUM". */
    private String accountType;
    /**
     * Boxed Boolean (KHÔNG dùng primitive): với primitive `boolean isPremium`, Lombok sinh getter
     * `isPremium()` và Jackson cắt prefix "is" → JSON field thành "premium", lệch với FE đọc
     * `ent.isPremium`. Boxed Boolean → getter `getIsPremium()` → JSON "isPremium" (khớp UserResponse).
     */
    private Boolean isPremium;
    private int trialCreditBalance;
    private BigDecimal paidCreditBalance;
    private Limits limits;

    @Getter
    @Builder
    public static class Limits {
        private int maxTripDays;
        private int maxStyles;
        private boolean canExportPdf;
        private boolean canRegenerate;
    }
}
