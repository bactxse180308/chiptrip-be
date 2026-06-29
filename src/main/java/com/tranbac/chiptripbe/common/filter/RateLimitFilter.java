package com.tranbac.chiptripbe.common.filter;

import com.tranbac.chiptripbe.common.config.RateLimitProperties;
import com.tranbac.chiptripbe.common.security.UserPrincipal;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sliding-window rate limiter. Chạy sau JwtAuthFilter (qua FilterChainProxy) nên
 * SecurityContext đã có UserPrincipal cho request đã xác thực.
 */
@Component
@Order(10)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String TOO_MANY_BODY =
            "{\"data\":null,\"error\":{\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau.\"}}";

    private static final long IDLE_TTL_MS = Duration.ofMinutes(30).toMillis();

    private final RateLimitProperties props;
    private final Map<String, Holder> buckets = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rate-limit-cleaner");
        t.setDaemon(true);
        return t;
    });

    private enum Category { LOGIN, REGISTER, FORGOT, GENERATE, DEFAULT }

    private static final class Holder {
        final Bucket bucket;
        volatile long lastAccess;

        Holder(Bucket bucket, long now) {
            this.bucket = bucket;
            this.lastAccess = now;
        }
    }

    @PostConstruct
    void startCleanup() {
        cleaner.scheduleAtFixedRate(this::evictIdle, 10, 10, TimeUnit.MINUTES);
    }

    @PreDestroy
    void stopCleanup() {
        cleaner.shutdownNow();
    }

    private void evictIdle() {
        long now = System.currentTimeMillis();
        buckets.entrySet().removeIf(e -> now - e.getValue().lastAccess > IDLE_TTL_MS);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // CORS preflight không tính rate-limit
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Category category = resolveCategory(request);
        String key = category.name() + ":" + resolveKey(request, category);

        long now = System.currentTimeMillis();
        Holder holder = buckets.computeIfAbsent(key, k -> new Holder(newBucket(category), now));
        holder.lastAccess = now;

        if (holder.bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            response.setStatus(429); // HTTP 429 Too Many Requests (no jakarta constant)
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(TOO_MANY_BODY);
        }
    }

    private Category resolveCategory(HttpServletRequest request) {
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            String uri = request.getRequestURI();
            if (uri.endsWith("/api/v1/auth/login")) return Category.LOGIN;
            if (uri.endsWith("/api/v1/auth/register")) return Category.REGISTER;
            if (uri.endsWith("/api/v1/auth/forgot-password")) return Category.FORGOT;
            if (uri.endsWith("/api/v1/trips/generate")) return Category.GENERATE;
            if (uri.endsWith("/api/v1/trips/generate-async")) return Category.GENERATE;
        }
        return Category.DEFAULT;
    }

    /** login/register/forgot khóa theo IP; còn lại theo userId nếu đã xác thực, ngược lại theo IP. */
    private String resolveKey(HttpServletRequest request, Category category) {
        if (category == Category.LOGIN || category == Category.REGISTER || category == Category.FORGOT) {
            return "ip:" + getClientIp(request);
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            return "user:" + principal.getId();
        }
        return "ip:" + getClientIp(request);
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private Bucket newBucket(Category category) {
        int limit;
        int windowMinutes;
        switch (category) {
            case LOGIN -> { limit = props.getLoginLimit(); windowMinutes = props.getLoginWindowMinutes(); }
            case REGISTER -> { limit = props.getRegisterLimit(); windowMinutes = props.getRegisterWindowMinutes(); }
            case FORGOT -> { limit = props.getForgotPasswordLimit(); windowMinutes = props.getForgotPasswordWindowMinutes(); }
            case GENERATE -> { limit = props.getGenerateLimit(); windowMinutes = props.getGenerateWindowMinutes(); }
            default -> { limit = props.getDefaultLimit(); windowMinutes = props.getDefaultWindowMinutes(); }
        }
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(limit)
                .refillIntervally(limit, Duration.ofMinutes(windowMinutes))
                .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }
}
