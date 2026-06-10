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

    private OpenAiCompat openaiCompat = new OpenAiCompat();
    private int maxRetries = 1;
    private int timeoutSeconds = 90;
    private Pricing pricing = new Pricing();

    @Getter
    @Setter
    public static class OpenAiCompat {
        private String apiKey;
        private String model = "gemini-3.1-pro-preview";
        private String baseUrl;
    }

    @Getter
    @Setter
    public static class Pricing {
        private double inputUsdPer1m = 3.00;
        private double outputUsdPer1m = 18.00;
    }
}
