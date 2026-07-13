package com.tranbac.chiptripbe.common.resilience;

import com.tranbac.chiptripbe.common.config.ProviderResilienceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void routeCooldownDoesNotBlockGeocodeOrAutocomplete() {
        ProviderResilience resilience = new ProviderResilience(new ProviderResilienceProperties());
        resilience.cooldown(ProviderResilience.GOONG_ROUTE, Duration.ofSeconds(60), "route 429");

        assertEquals("geocode", resilience.execute(ProviderResilience.GOONG_GEOCODE,
                () -> "geocode", () -> "fallback"));
        assertEquals("autocomplete", resilience.execute(ProviderResilience.GOONG_AUTOCOMPLETE,
                () -> "autocomplete", () -> "fallback"));
        assertEquals("fallback", resilience.execute(ProviderResilience.GOONG_ROUTE,
                () -> "route", () -> "fallback"));
    }

    @Test
    void cooldownRemainingReturnsLongestCooldownOnlyAmongRequestedProviders() {
        ProviderResilience resilience = new ProviderResilience(new ProviderResilienceProperties());
        resilience.cooldown(ProviderResilience.SERPAPI, Duration.ofSeconds(10), "serp quota");
        resilience.cooldown(ProviderResilience.GOONG_GEOCODE, Duration.ofSeconds(20), "geocode rate");
        resilience.cooldown(ProviderResilience.GOONG_ROUTE, Duration.ofSeconds(60), "route rate");

        Duration remaining = resilience.cooldownRemaining(
                ProviderResilience.SERPAPI, ProviderResilience.GOONG_GEOCODE);

        assertTrue(remaining.compareTo(Duration.ofSeconds(15)) > 0);
        assertTrue(remaining.compareTo(Duration.ofSeconds(20)) <= 0);
        assertEquals(Duration.ZERO,
                resilience.cooldownRemaining(ProviderResilience.GOONG_AUTOCOMPLETE));
    }

    @Test
    void openCircuitRejectsBeforeRunningAdmissionGate() {
        ProviderResilienceProperties properties = new ProviderResilienceProperties();
        properties.getCircuitBreaker().setSlidingWindowSize(2);
        properties.getCircuitBreaker().setMinimumNumberOfCalls(2);
        properties.getCircuitBreaker().setFailureRateThreshold(50.0f);
        properties.getCircuitBreaker().setWaitDurationInOpenStateSeconds(60);
        ProviderResilience resilience = new ProviderResilience(properties);
        AtomicInteger admissionCalls = new AtomicInteger();

        assertThrows(RuntimeException.class, () -> resilience.execute(ProviderResilience.GOONG_GEOCODE,
                () -> { throw new RuntimeException("down"); }, () -> "fallback"));
        assertThrows(RuntimeException.class, () -> resilience.execute(ProviderResilience.GOONG_GEOCODE,
                () -> { throw new RuntimeException("down"); }, () -> "fallback"));

        String result = resilience.executeWithAdmission(ProviderResilience.GOONG_GEOCODE,
                () -> {
                    admissionCalls.incrementAndGet();
                    return true;
                },
                () -> "provider",
                () -> "fallback");

        assertEquals("fallback", result);
        assertEquals(0, admissionCalls.get());
    }

    @Test
    void rejectedAdmissionDoesNotCountAsProviderFailure() {
        ProviderResilienceProperties properties = new ProviderResilienceProperties();
        properties.getCircuitBreaker().setSlidingWindowSize(2);
        properties.getCircuitBreaker().setMinimumNumberOfCalls(2);
        properties.getCircuitBreaker().setFailureRateThreshold(50.0f);
        ProviderResilience resilience = new ProviderResilience(properties);

        assertEquals("fallback", resilience.executeWithAdmission(ProviderResilience.GOONG_GEOCODE,
                () -> false, () -> "provider", () -> "fallback"));
        assertEquals("fallback", resilience.executeWithAdmission(ProviderResilience.GOONG_GEOCODE,
                () -> false, () -> "provider", () -> "fallback"));

        assertEquals("provider", resilience.executeWithAdmission(ProviderResilience.GOONG_GEOCODE,
                () -> true, () -> "provider", () -> "fallback"));
    }
}
