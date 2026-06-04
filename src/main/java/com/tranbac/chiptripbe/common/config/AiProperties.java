package com.tranbac.chiptripbe.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ai")
@Getter
@Setter
public class AiProperties {

    private GeminiConfig gemini = new GeminiConfig();
    private int maxRetries = 2;
    private int timeoutSeconds = 60;
    private Pricing pricing = new Pricing();

    @Getter
    @Setter
    public static class GeminiConfig {
        private String apiKey;
        private String model = "gemini-2.5-flash";
        private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";
    }

    @Getter
    @Setter
    public static class Pricing {
        private double inputUsdPer1m = 0.075;
        private double outputUsdPer1m = 0.30;
    }
}
