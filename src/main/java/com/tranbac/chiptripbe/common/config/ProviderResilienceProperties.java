package com.tranbac.chiptripbe.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.provider-resilience")
@Getter
@Setter
public class ProviderResilienceProperties {

    private boolean circuitBreakerEnabled = true;
    private int retryAfterDefaultSeconds = 60;
    private int retryAfterMaxSeconds = 900;
    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    @Getter
    @Setter
    public static class CircuitBreaker {
        private int slidingWindowSize = 20;
        private int minimumNumberOfCalls = 6;
        private float failureRateThreshold = 50.0f;
        private float slowCallRateThreshold = 50.0f;
        private int waitDurationInOpenStateSeconds = 30;
        private int permittedNumberOfCallsInHalfOpenState = 2;
        private int goongSlowCallSeconds = 2;
        private int serpApiSlowCallSeconds = 6;
        private int llmSlowCallSeconds = 45;
    }
}
