package com.tranbac.chiptripbe.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.ai")
@Getter
@Setter
public class AiProperties {

    private OpenAiCompat openaiCompat = new OpenAiCompat();
    private int maxRetries = 1;
    private int timeoutSeconds = 90;

    /** Số lần CHỜ+thử lại trong cùng request khi provider trả 503 "high demand" (spike thường qua nhanh). */
    private int busyMaxRetries = 3;
    /** Số giây chờ giữa các lần thử lại khi 503 (chờ THẬT để model hạ tải, khác backoff nội dung ms). */
    private int busyBackoffSeconds = 4;
    /** Cooldown NGẮN đặt cho circuit LLM sau khi hết lượt chờ 503 — thay cho honor-retry-after 60s cũ. */
    private int busyCooldownSeconds = 10;

    private Pricing pricing = new Pricing();

    @Getter
    @Setter
    public static class OpenAiCompat {
        /** Nhiều API key Gemini (ngăn bởi dấu phẩy ở AI_API_KEYS) để tự xoay vòng khi một key hết hạn mức. */
        private List<String> apiKeys = new ArrayList<>();
        private String model = "gemini-2.5-flash";
        private String baseUrl;
    }

    @Getter
    @Setter
    public static class Pricing {
        private double inputUsdPer1m = 3.00;
        private double outputUsdPer1m = 18.00;
    }
}
