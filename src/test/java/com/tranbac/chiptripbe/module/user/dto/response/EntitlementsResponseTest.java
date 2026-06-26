package com.tranbac.chiptripbe.module.user.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Khoá contract JSON mà FE phụ thuộc: {@code ent.isPremium}.
 * Bug: primitive {@code boolean isPremium} + Lombok getter {@code isPremium()} khiến Jackson cắt
 * prefix "is" → JSON "premium", FE đọc {@code ent.isPremium} = undefined → khoá nhầm tính năng
 * Premium dù user đã nạp tiền (badge navbar vẫn hiện Premium vì lấy fallback từ /users/me).
 */
class EntitlementsResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serialize_premiumFlag_usesIsPremiumKey_notPremium() throws Exception {
        EntitlementsResponse res = EntitlementsResponse.builder()
                .accountType("PREMIUM")
                .isPremium(true)
                .trialCreditBalance(1)
                .paidCreditBalance(new BigDecimal("1.00"))
                .limits(EntitlementsResponse.Limits.builder()
                        .maxTripDays(10)
                        .maxStyles(Integer.MAX_VALUE)
                        .canExportPdf(true)
                        .canRegenerate(true)
                        .build())
                .build();

        JsonNode json = mapper.readTree(mapper.writeValueAsString(res));

        assertTrue(json.has("isPremium"), "FE đọc ent.isPremium — JSON phải có key 'isPremium'");
        assertTrue(json.get("isPremium").asBoolean(), "isPremium phải = true");
        assertFalse(json.has("premium"), "KHÔNG được serialize thành 'premium' (Jackson cắt prefix 'is')");
    }
}
