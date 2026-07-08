package com.tranbac.chiptripbe.common.resilience;

import com.tranbac.chiptripbe.common.config.ProviderResilienceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderResilienceTest {

    @Test
    void honorRetryAfter_activeCooldownFastFailsToFallback() {
        ProviderResilience resilience = new ProviderResilience(new ProviderResilienceProperties());
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "60");
        AtomicInteger supplierCalls = new AtomicInteger();

        resilience.honorRetryAfter(ProviderResilience.SERPAPI, headers, Duration.ofSeconds(1), "test 429");
        String result = resilience.execute(ProviderResilience.SERPAPI,
                () -> {
                    supplierCalls.incrementAndGet();
                    return "provider";
                },
                () -> "fallback");

        assertEquals("fallback", result);
        assertEquals(0, supplierCalls.get());
    }

    @Test
    void repeatedFailuresOpenCircuitThenFastFail() {
        ProviderResilienceProperties properties = new ProviderResilienceProperties();
        properties.getCircuitBreaker().setSlidingWindowSize(2);
        properties.getCircuitBreaker().setMinimumNumberOfCalls(2);
        properties.getCircuitBreaker().setFailureRateThreshold(50.0f);
        properties.getCircuitBreaker().setWaitDurationInOpenStateSeconds(60);
        ProviderResilience resilience = new ProviderResilience(properties);

        assertThrows(RuntimeException.class, () -> resilience.execute(ProviderResilience.GOONG,
                () -> { throw new RuntimeException("down"); },
                () -> "fallback"));
        assertThrows(RuntimeException.class, () -> resilience.execute(ProviderResilience.GOONG,
                () -> { throw new RuntimeException("down"); },
                () -> "fallback"));

        String result = resilience.execute(ProviderResilience.GOONG,
                () -> "provider",
                () -> "fallback");

        assertEquals("fallback", result);
    }
}
