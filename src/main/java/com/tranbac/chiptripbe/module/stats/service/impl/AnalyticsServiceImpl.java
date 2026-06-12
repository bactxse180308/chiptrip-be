package com.tranbac.chiptripbe.module.stats.service.impl;

import com.tranbac.chiptripbe.common.config.PosthogProperties;
import com.tranbac.chiptripbe.module.stats.dto.response.DailyCountResponse;
import com.tranbac.chiptripbe.module.stats.dto.response.EventCountResponse;
import com.tranbac.chiptripbe.module.stats.service.AnalyticsService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gọi PostHog Query API (HogQL) phía server, giấu personal key khỏi frontend.
 * Fail-soft: lỗi/chưa cấu hình → trả về list rỗng (giống GoongClient).
 */
@Slf4j
@Service
@RequiredArgsConstructor
class AnalyticsServiceImpl implements AnalyticsService {

    private static final int MAX_DAYS = 365;

    /** Thứ tự bước funnel — phải khớp nhãn ở frontend. */
    private static final List<String> FUNNEL_STEPS = List.of(
            "sign_up", "generate_started", "generate_succeeded",
            "trip_saved", "booking_click", "publish",
            "purchase_started", "purchase_succeeded");

    private final WebClient.Builder webClientBuilder;
    private final PosthogProperties properties;

    private WebClient client;

    @PostConstruct
    void init() {
        client = webClientBuilder.baseUrl(properties.getApiHost()).build();
    }

    @Override
    public List<DailyCountResponse> getPageviewsByDay(int days) {
        int d = clampDays(days);
        String hogql = "SELECT toString(toDate(timestamp)) AS day, count() AS cnt " +
                "FROM events WHERE event = '$pageview' AND timestamp >= now() - INTERVAL " + d + " DAY " +
                "GROUP BY day ORDER BY day";
        return runQuery(hogql).stream()
                .map(r -> DailyCountResponse.builder()
                        .date(String.valueOf(r.get(0)))
                        .count(asLong(r.get(1)))
                        .build())
                .toList();
    }

    @Override
    public List<EventCountResponse> getEventCounts(int days) {
        int d = clampDays(days);
        String hogql = "SELECT event, count() AS cnt FROM events " +
                "WHERE event != '$pageview' AND timestamp >= now() - INTERVAL " + d + " DAY " +
                "GROUP BY event ORDER BY cnt DESC LIMIT 20";
        return runQuery(hogql).stream()
                .map(r -> EventCountResponse.builder()
                        .event(String.valueOf(r.get(0)))
                        .count(asLong(r.get(1)))
                        .build())
                .toList();
    }

    @Override
    public List<EventCountResponse> getFunnel(int days) {
        int d = clampDays(days);
        String inList = "'" + String.join("', '", FUNNEL_STEPS) + "'";
        String hogql = "SELECT event, count(DISTINCT person_id) AS users FROM events " +
                "WHERE event IN (" + inList + ") AND timestamp >= now() - INTERVAL " + d + " DAY " +
                "GROUP BY event";

        Map<String, Long> counts = new HashMap<>();
        for (List<Object> r : runQuery(hogql)) {
            counts.put(String.valueOf(r.get(0)), asLong(r.get(1)));
        }
        return FUNNEL_STEPS.stream()
                .map(ev -> EventCountResponse.builder()
                        .event(ev)
                        .count(counts.getOrDefault(ev, 0L))
                        .build())
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<List<Object>> runQuery(String hogql) {
        if (!properties.isConfigured()) {
            log.warn("PostHog chưa cấu hình (app.posthog.project-id / personal-key) — trả về rỗng");
            return List.of();
        }
        try {
            Map<String, Object> body = Map.of(
                    "query", Map.of("kind", "HogQLQuery", "query", hogql));

            Map<String, Object> resp = client.post()
                    .uri("/api/projects/{id}/query/", properties.getProjectId())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getPersonalKey())
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .block();

            if (resp == null) return List.of();
            if (resp.get("results") instanceof List<?> results) {
                return (List<List<Object>>) results;
            }
            return List.of();
        } catch (Exception e) {
            log.warn("PostHog query failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static int clampDays(int days) {
        if (days < 1) return 1;
        return Math.min(days, MAX_DAYS);
    }

    private static long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
