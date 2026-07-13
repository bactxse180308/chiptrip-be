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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderResilience {

    public static final String GOONG = "goong";
    public static final String GOONG_GEOCODE = "goong-geocode";
    public static final String GOONG_ROUTE = "goong-route";
    public static final String GOONG_AUTOCOMPLETE = "goong-autocomplete";
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

    /**
     * Variant có admission gate (ví dụ rate limiter). Circuit breaker cấp quyền trước để OPEN
     * fast-fail mà không chiếm permit chung; thời gian chờ gate không bị tính là độ trễ provider.
     */
    public <T> T executeWithAdmission(String provider, BooleanSupplier admission,
                                      Supplier<T> supplier, Supplier<T> fallback) {
        ProviderState state = state(provider);
        long now = System.currentTimeMillis();
        if (state.cooldownUntilMs().get() > now) return fallback.get();

        if (!properties.isCircuitBreakerEnabled()) {
            if (!admission.getAsBoolean()) return fallback.get();
            if (state.cooldownUntilMs().get() > System.currentTimeMillis()) return fallback.get();
            return supplier.get();
        }

        CircuitBreaker circuitBreaker = state.circuitBreaker();
        if (!circuitBreaker.tryAcquirePermission()) {
            log.warn("{} circuit breaker OPEN, using fallback", provider);
            return fallback.get();
        }

        boolean admitted;
        try {
            admitted = admission.getAsBoolean();
        } catch (RuntimeException | Error e) {
            circuitBreaker.releasePermission();
            throw e;
        }
        if (!admitted || state.cooldownUntilMs().get() > System.currentTimeMillis()) {
            circuitBreaker.releasePermission();
            return fallback.get();
        }

        long startedAt = System.nanoTime();
        try {
            T result = supplier.get();
            circuitBreaker.onSuccess(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
            return result;
        } catch (RuntimeException | Error e) {
            circuitBreaker.onError(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS, e);
            throw e;
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

    /** Thời gian cooldown dài nhất còn lại trong đúng các provider được hỏi. */
    public Duration cooldownRemaining(String... providers) {
        if (providers == null || providers.length == 0) return Duration.ZERO;
        long now = System.currentTimeMillis();
        long maxRemainingMs = 0;
        for (String provider : providers) {
            long remainingMs = state(provider).cooldownUntilMs().get() - now;
            maxRemainingMs = Math.max(maxRemainingMs, remainingMs);
        }
        return maxRemainingMs > 0 ? Duration.ofMillis(maxRemainingMs) : Duration.ZERO;
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
        if (provider.startsWith(GOONG)) {
            return Duration.ofSeconds(Math.max(1, cb.getGoongSlowCallSeconds()));
        }
        return switch (provider) {
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
