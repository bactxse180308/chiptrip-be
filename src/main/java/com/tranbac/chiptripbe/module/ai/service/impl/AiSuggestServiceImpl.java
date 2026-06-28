package com.tranbac.chiptripbe.module.ai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.common.config.AiProperties;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.ai.dto.request.SuggestDestinationsRequest;
import com.tranbac.chiptripbe.module.ai.dto.response.DestinationSuggestion;
import com.tranbac.chiptripbe.module.ai.entity.AiUsage;
import com.tranbac.chiptripbe.module.ai.repository.AiUsageRepository;
import com.tranbac.chiptripbe.module.ai.service.AiKeyPool;
import com.tranbac.chiptripbe.module.ai.service.AiSuggestService;
import com.tranbac.chiptripbe.module.user.entity.User;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
class AiSuggestServiceImpl implements AiSuggestService {

    private final WebClient.Builder webClientBuilder;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final AiUsageRepository aiUsageRepository;
    private final UserRepository userRepository;
    private final AiKeyPool aiKeyPool;

    private WebClient aiApiClient;

    @PostConstruct
    void init() {
        // Không gắn Authorization cố định: key set theo từng request để xoay vòng khi một key hết hạn mức.
        aiApiClient = webClientBuilder
                .baseUrl(aiProperties.getOpenaiCompat().getBaseUrl())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    @Transactional
    public List<DestinationSuggestion> suggest(Long userId, SuggestDestinationsRequest request) {
        Map<String, Object> body = buildRequest(request);
        // Đủ lượt để xoay qua mọi key (khi quota) cộng thêm ngân sách retry cho lỗi tạm thời.
        int maxAttempts = aiKeyPool.size() + aiProperties.getMaxRetries();

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            String apiKey = aiKeyPool.current();
            boolean lastAttempt = attempt == maxAttempts - 1;
            try {
                Map<String, Object> response = callLlm(body, apiKey);
                String content = extractContent(response);
                List<DestinationSuggestion> suggestions = parseAndValidate(content);
                logUsage(userId, response);
                return suggestions;

            } catch (SuggestNonRetryableException e) {
                log.error("Non-retryable AI suggest error: {}", e.getMessage());
                throw AppException.badRequest("Không thể gợi ý điểm đến: " + e.getMessage());

            } catch (SuggestKeyExhaustedException e) {
                aiKeyPool.rotate(apiKey);
                if (lastAttempt) {
                    log.error("AI suggest failed: mọi key đều bị từ chối/hết hạn mức");
                    throw AppException.internal("Hệ thống AI tạm hết lượt. Vui lòng thử lại sau.");
                }
                log.warn("Key AI bị từ chối (quota/invalid), đổi key và thử lại: {}", e.getMessage());
                // đổi key → thử lại ngay, không backoff

            } catch (SuggestRetryableException | WebClientException e) {
                if (lastAttempt) {
                    log.error("AI suggest failed after {} attempts", attempt + 1, e);
                    throw AppException.internal("AI không thể gợi ý điểm đến lúc này. Vui lòng thử lại sau.");
                }
                log.warn("AI suggest attempt {}/{} failed, retrying: {}",
                        attempt + 1, maxAttempts, e.getMessage());
                sleepBackoff(Math.min(attempt, aiProperties.getMaxRetries()));
            }
        }
        throw AppException.internal("AI suggest failed");
    }

    /** Exponential backoff giữa các lần retry: 500ms, 1s, 2s, ... */
    private void sleepBackoff(int attempt) {
        try {
            long backoffMs = (long) (Math.pow(2, attempt) * 500);
            Thread.sleep(backoffMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw AppException.internal("Gợi ý điểm đến bị gián đoạn");
        }
    }

    // ─── Build request (OpenAI Chat Completions format) ───────────────────────

    private Map<String, Object> buildRequest(SuggestDestinationsRequest request) {
        // OpenAI JSON mode yêu cầu root JSON object → bọc array trong field "destinations"
        String systemPrompt = """
                Bạn là trợ lý du lịch Việt Nam.
                Quy tắc bắt buộc:
                - Gợi ý 3 đến 5 điểm đến TRONG NƯỚC (Việt Nam) phù hợp với phong cách, ngân sách và số ngày.
                - Chỉ trả về JSON hợp lệ, không markdown, không giải thích ngoài JSON.
                - Root là object với đúng một field "destinations" là mảng.
                - Mỗi phần tử có: name (tên điểm đến có thật ở Việt Nam), emoji (1 emoji phù hợp), desc (mô tả tiếng Việt dưới 30 từ).
                - Cấu trúc:
                  { "destinations": [ { "name": "string", "emoji": "string", "desc": "string" } ] }
                """;

        String stylesText = (request.getStyles() != null && !request.getStyles().isEmpty())
                ? String.join(", ", request.getStyles())
                : "không có yêu cầu đặc biệt";

        String userPrompt = String.format("""
                Gợi ý điểm đến với thông tin:
                - Phong cách: %s
                - Ngân sách: %,d VNĐ
                - Số ngày: %d
                """, stylesText, request.getBudgetVnd(), request.getDays());

        return Map.of(
                "model", aiProperties.getOpenaiCompat().getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "response_format", Map.of("type", "json_object"),
                "temperature", 0.7
        );
    }

    // ─── HTTP call ──────────────────────────────────────────────────────────

    private Map<String, Object> callLlm(Map<String, Object> body, String apiKey) {
        return aiApiClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.value() == 429 || status.value() == 401 || status.value() == 403,
                        response -> response.bodyToMono(String.class)
                                .map(errorBody -> (Throwable) new SuggestKeyExhaustedException(
                                        "AI HTTP " + response.statusCode().value() + ": " + errorBody)))
                .onStatus(status -> status.value() == 400,
                        response -> response.bodyToMono(String.class)
                                .map(errorBody -> (Throwable) new SuggestNonRetryableException(
                                        "AI HTTP 400: " + errorBody)))
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .map(errorBody -> (Throwable) new SuggestRetryableException(
                                        "AI HTTP " + response.statusCode().value() + ": " + errorBody)))
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(aiProperties.getTimeoutSeconds()))
                .onErrorMap(TimeoutException.class,
                        e -> new SuggestRetryableException("AI timeout sau " + aiProperties.getTimeoutSeconds() + "s"))
                .onErrorMap(WebClientRequestException.class,
                        e -> new SuggestRetryableException("Connection error: " + e.getMessage()))
                .block();
    }

    // ─── Parse & validate ───────────────────────────────────────────────────

    private List<DestinationSuggestion> parseAndValidate(String content) {
        Map<String, Object> root;
        try {
            root = objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new SuggestRetryableException("JSON parse failed: " + e.getMessage());
        }
        Object rawDestinations = root.get("destinations");
        if (!(rawDestinations instanceof List<?>)) {
            throw new SuggestRetryableException("AI trả về JSON thiếu field 'destinations' kiểu array");
        }

        List<DestinationSuggestion> suggestions = objectMapper.convertValue(
                rawDestinations, new TypeReference<List<DestinationSuggestion>>() {});

        if (suggestions == null || suggestions.isEmpty()) {
            throw new SuggestRetryableException("AI trả về danh sách gợi ý rỗng");
        }
        for (DestinationSuggestion s : suggestions) {
            if (s.getName() == null || s.getName().isBlank()) {
                throw new SuggestRetryableException("Gợi ý thiếu tên điểm đến");
            }
        }
        // giới hạn 5 phần tử
        return suggestions.size() > 5 ? suggestions.subList(0, 5) : suggestions;
    }

    // ─── Response helpers (OpenAI Chat Completions format) ──────────────────

    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> response) {
        if (response == null) throw new SuggestRetryableException("AI trả về response null");
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty()) throw new SuggestRetryableException("AI trả về choices rỗng");
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        if (message == null) throw new SuggestRetryableException("AI trả về message null");
        String content = (String) message.get("content");
        if (content == null || content.isBlank()) throw new SuggestRetryableException("AI trả về content rỗng");
        return content;
    }

    /** Trả về [promptTokens, completionTokens]. */
    @SuppressWarnings("unchecked")
    private int[] extractTokenCount(Map<String, Object> response) {
        try {
            Map<String, Object> usage = (Map<String, Object>) response.get("usage");
            int prompt = ((Number) usage.get("prompt_tokens")).intValue();
            int completion = ((Number) usage.get("completion_tokens")).intValue();
            return new int[]{prompt, completion};
        } catch (RuntimeException e) {
            log.warn("Không parse được token count: {}", e.getMessage());
            return new int[]{0, 0};
        }
    }

    private void logUsage(Long userId, Map<String, Object> response) {
        int[] tokens = extractTokenCount(response);
        int promptTokens = tokens[0];
        int completionTokens = tokens[1];
        BigDecimal costUsd = BigDecimal.valueOf(
                (double) promptTokens / 1_000_000 * aiProperties.getPricing().getInputUsdPer1m()
                + (double) completionTokens / 1_000_000 * aiProperties.getPricing().getOutputUsdPer1m()
        ).setScale(6, RoundingMode.HALF_UP);

        User user = userRepository.getReferenceById(userId);
        aiUsageRepository.save(AiUsage.builder()
                .user(user)
                .provider("gemini")
                .tokensIn(promptTokens)
                .tokensOut(completionTokens)
                .costUsd(costUsd)
                .build());
    }

    // ─── Internal exception types ───────────────────────────────────────────

    static class SuggestRetryableException extends RuntimeException {
        SuggestRetryableException(String msg) { super(msg); }
    }

    static class SuggestNonRetryableException extends RuntimeException {
        SuggestNonRetryableException(String msg) { super(msg); }
    }

    /** Key bị từ chối (429 quota / 401-403 invalid) → xoay sang key khác rồi thử lại. */
    static class SuggestKeyExhaustedException extends RuntimeException {
        SuggestKeyExhaustedException(String msg) { super(msg); }
    }
}
