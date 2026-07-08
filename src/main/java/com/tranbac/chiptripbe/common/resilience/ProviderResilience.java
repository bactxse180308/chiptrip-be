package com.tranbac.chiptripbe.common.resilience;

import com.tranbac.chiptripbe.common.config.ProviderResilienceProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderResilience {

    public static final String GOONG = "goong";
    public static final String SERPAPI = "serpapi";
    public static final String LLM = "llm";

    private final ProviderResilienceProperties properties;
    private final ConcurrentHashMap<String, ProviderState> states = new ConcurrentHashMap<>();

    public <T> T execute(String provider, Supplier<T> supplier, Supplier<T> fallback) {
        ProviderState state = state(provider);
        long now = System.currentTimeMillis();
        long cooldownUntil = state.cooldownUntilMs().get();
        if (cooldownUntil > now) {
            log.debug("{} provider cooldown active for {} ms", provider, cooldownUntil - now);
            return fallback.get();
        }

        Supplier<T> guarded = properties.isCircuitBreakerEnabled()
                ? CircuitBreaker.decorateSupplier(state.circuitBreaker(), supplier)
                : supplier;
        try {
            return guarded.get();
        } catch (CallNotPermittedException e) {
            log.warn("{} circuit breaker OPEN, using fallback", provider);
            return fallback.get();
        }
    }

    public void honorRetryAfter(String provider, HttpHeaders headers, Duration fallbackDelay, String reason) {
        Duration delay = retryAfterDelay(headers, fallbackDelay);
        if (delay == null || delay.isNegative() || delay.isZero()) return;
        long maxSeconds = Math.max(1, properties.getRetryAfterMaxSeconds());
        if (delay.getSeconds() > maxSeconds) {
            delay = Duration.ofSeconds(maxSeconds);
        }
        cooldown(provider, delay, reason);
    }

    public void cooldown(String provider, Duration delay, String reason) {
        if (delay == null || delay.isNegative() || delay.isZero()) return;
        long until = System.currentTimeMillis() + delay.toMillis();
        ProviderState state = state(provider);
        state.cooldownUntilMs().accumulateAndGet(until, Math::max);
        log.warn("{} provider cooldown {}s: {}", provider, delay.toSeconds(), reason);
    }

    private ProviderState state(String provider) {
        String key = provider == null || provider.isBlank()
                ? "unknown"
                : provider.toLowerCase(Locale.ROOT);
        return states.computeIfAbsent(key, p -> new ProviderState(new AtomicLong(0), CircuitBreaker.of(p, configFor(p))));
    }

    private CircuitBreakerConfig configFor(String provider) {
        ProviderResilienceProperties.CircuitBreaker cb = properties.getCircuitBreaker();
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(Math.max(2, cb.getSlidingWindowSize()))
                .minimumNumberOfCalls(Math.max(2, cb.getMinimumNumberOfCalls()))
                .failureRateThreshold(cb.getFailureRateThreshold())
                .slowCallRateThreshold(cb.getSlowCallRateThreshold())
                .slowCallDurationThreshold(slowCallThreshold(provider, cb))
                .waitDurationInOpenState(Duration.ofSeconds(Math.max(1, cb.getWaitDurationInOpenStateSeconds())))
                .permittedNumberOfCallsInHalfOpenState(Math.max(1, cb.getPermittedNumberOfCallsInHalfOpenState()))
                .build();
    }

    private Duration slowCallThreshold(String provider, ProviderResilienceProperties.CircuitBreaker cb) {
        return switch (provider) {
            case GOONG -> Duration.ofSeconds(Math.max(1, cb.getGoongSlowCallSeconds()));
            case SERPAPI -> Duration.ofSeconds(Math.max(1, cb.getSerpApiSlowCallSeconds()));
            case LLM -> Duration.ofSeconds(Math.max(1, cb.getLlmSlowCallSeconds()));
            default -> Duration.ofSeconds(5);
        };
    }

    private Duration retryAfterDelay(HttpHeaders headers, Duration fallbackDelay) {
        String retryAfter = headers != null ? headers.getFirst(HttpHeaders.RETRY_AFTER) : null;
        if (retryAfter == null || retryAfter.isBlank()) {
            return fallbackDelay != null
                    ? fallbackDelay
                    : Duration.ofSeconds(Math.max(1, properties.getRetryAfterDefaultSeconds()));
        }
        String value = retryAfter.trim();
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value)));
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Duration.between(Instant.now(), retryAt);
            } catch (RuntimeException ignoredDate) {
                return fallbackDelay != null
                        ? fallbackDelay
                        : Duration.ofSeconds(Math.max(1, properties.getRetryAfterDefaultSeconds()));
            }
        }
    }

    private record ProviderState(AtomicLong cooldownUntilMs, CircuitBreaker circuitBreaker) {}
}
