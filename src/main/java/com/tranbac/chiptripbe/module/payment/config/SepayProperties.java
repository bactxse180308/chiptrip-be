package com.tranbac.chiptripbe.module.payment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "app.sepay")
@Getter
@Setter
public class SepayProperties {

    /** API key cấu hình trên dashboard SePay; webhook gửi qua header "Authorization: Apikey <key>". */
    private String apiKey;

    /** Số tài khoản ngân hàng nhận tiền (đã liên kết SePay). */
    private String bankAccount;

    /** Mã/short name ngân hàng cho VietQR (vd "Vietcombank", "VCB", "MBBank"). */
    private String bankCode = "Vietcombank";

    /** Tên chủ tài khoản (hiển thị cho user). */
    private String accountHolder = "CHIP TRIP";

    /** Endpoint sinh ảnh VietQR động của SePay. */
    private String qrBaseUrl = "https://qr.sepay.vn/img";

    /** Kiểu hiển thị QR: blank | compact | qronly | standee. */
    private String qrTemplate = "compact";

    private String orderCodePrefix = "CHIP";

    /** Thời gian sống của 1 order (phút) — dùng cho countdown phía FE. */
    private int orderExpiryMinutes = 15;

    /** Map: planCode (uppercase) → {priceVnd, credits}. */
    private Map<String, PricingPlan> plans = new LinkedHashMap<>();

    public PricingPlan findPlan(String planCode) {
        if (planCode == null) return null;
        return plans.get(planCode.toUpperCase());
    }

    public String getNormalizedOrderCodePrefix() {
        String normalized = orderCodePrefix == null ? "" : orderCodePrefix.trim().toUpperCase();
        return normalized.matches("[A-Z0-9]{2,12}") ? normalized : "CHIP";
    }

    @Getter
    @Setter
    public static class PricingPlan {
        private long priceVnd;
        private int credits;
    }
}
