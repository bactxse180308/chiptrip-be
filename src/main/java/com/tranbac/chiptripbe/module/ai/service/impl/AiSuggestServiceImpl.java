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
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
class AiSuggestServiceImpl implements AiSuggestService {

    private final WebClient.Builder webClientBuilder;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final AiUsageRepository aiUsageRepository;
    private final UserRepository userRepository;

    private WebClient geminiClient;

    @PostConstruct
    void init() {
        geminiClient = webClientBuilder
                .baseUrl(aiProperties.getGemini().getBaseUrl())
                .defaultHeader("x-goog-api-key", aiProperties.getGemini().getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    @Transactional
    public List<DestinationSuggestion> suggest(Long userId, SuggestDestinationsRequest request) {
        Map<String, Object> body = buildRequest(request);

        for (int attempt = 0; attempt <= aiProperties.getMaxRetries(); attempt++) {
            try {
                Map<String, Object> response = callGemini(body);
                String content = extractContent(response);
                List<DestinationSuggestion> suggestions = parseAndValidate(content);
                logUsage(userId, response);
                return suggestions;

            } catch (SuggestNonRetryableException e) {
                log.error("Non-retryable AI suggest error: {}", e.getMessage());
                throw AppException.badRequest("Không thể gợi ý điểm đến: " + e.getMessage());

            } catch (Exception e) {
                if (attempt == aiProperties.getMaxRetries()) {
                    log.error("AI suggest failed after {} attempts", attempt + 1, e);
                    throw AppException.internal("AI không thể gợi ý điểm đến lúc này. Vui lòng thử lại sau.");
                }
                log.warn("AI suggest attempt {}/{} failed, retrying: {}",
                        attempt + 1, aiProperties.getMaxRetries() + 1, e.getMessage());
            }
        }
        throw AppException.internal("AI suggest failed");
    }

    // ─── Build request ──────────────────────────────────────────────────────

    private Map<String, Object> buildRequest(SuggestDestinationsRequest request) {
        String systemPrompt = """
                Bạn là trợ lý du lịch Việt Nam.
                Quy tắc bắt buộc:
                - Gợi ý 3 đến 5 điểm đến TRONG NƯỚC (Việt Nam) phù hợp với phong cách, ngân sách và số ngày.
                - Chỉ trả về JSON array hợp lệ, không markdown, không giải thích ngoài JSON.
                - Mỗi phần tử có: name (tên điểm đến có thật ở Việt Nam), emoji (1 emoji phù hợp), desc (mô tả tiếng Việt dưới 30 từ).
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

        Map<String, Object> generationConfig = Map.of(
                "responseMimeType", "application/json",
                "responseSchema", buildResponseSchema(),
                "thinkingConfig", Map.of("thinkingBudget", 0)
        );

        return Map.of(
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt)))),
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                "generationConfig", generationConfig
        );
    }

    private Map<String, Object> buildResponseSchema() {
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("name", Map.of("type", "STRING"));
        itemProps.put("emoji", Map.of("type", "STRING"));
        itemProps.put("desc", Map.of("type", "STRING", "description", "Mô tả tiếng Việt dưới 30 từ"));

        Map<String, Object> itemSchema = new LinkedHashMap<>();
        itemSchema.put("type", "OBJECT");
        itemSchema.put("properties", itemProps);
        itemSchema.put("required", List.of("name", "emoji", "desc"));

        Map<String, Object> rootSchema = new LinkedHashMap<>();
        rootSchema.put("type", "ARRAY");
        rootSchema.put("items", itemSchema);
        return rootSchema;
    }

    // ─── HTTP call ──────────────────────────────────────────────────────────

    private Map<String, Object> callGemini(Map<String, Object> body) {
        String model = aiProperties.getGemini().getModel();
        return geminiClient.post()
                .uri("/models/" + model + ":generateContent")
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.value() == 400 || status.value() == 401 || status.value() == 403,
                        response -> response.bodyToMono(String.class)
                                .map(errorBody -> (Throwable) new SuggestNonRetryableException(
                                        "Gemini HTTP " + response.statusCode().value() + ": " + errorBody)))
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .map(errorBody -> (Throwable) new SuggestRetryableException(
                                        "Gemini HTTP " + response.statusCode().value())))
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(aiProperties.getTimeoutSeconds()))
                .onErrorMap(WebClientRequestException.class,
                        e -> new SuggestRetryableException("Connection error: " + e.getMessage()))
                .block();
    }

    // ─── Parse & validate ───────────────────────────────────────────────────

    private List<DestinationSuggestion> parseAndValidate(String content) {
        List<DestinationSuggestion> suggestions;
        try {
            suggestions = objectMapper.readValue(content, new TypeReference<List<DestinationSuggestion>>() {});
        } catch (JsonProcessingException e) {
            throw new SuggestRetryableException("JSON parse failed: " + e.getMessage());
        }
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

    // ─── Response helpers ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> response) {
        if (response == null) throw new SuggestRetryableException("Gemini trả về response null");
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
        if (candidates == null || candidates.isEmpty()) throw new SuggestRetryableException("Gemini trả về candidates rỗng");
        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        if (content == null) throw new SuggestRetryableException("Gemini trả về content null");
        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
        if (parts == null || parts.isEmpty()) throw new SuggestRetryableException("Gemini trả về parts rỗng");
        String text = (String) parts.get(0).get("text");
        if (text == null || text.isBlank()) throw new SuggestRetryableException("Gemini trả về text rỗng");
        return text;
    }

    @SuppressWarnings("unchecked")
    private int extractTokenCount(Map<String, Object> response, String field) {
        try {
            Map<String, Object> usageMetadata = (Map<String, Object>) response.get("usageMetadata");
            Object val = usageMetadata.get(field);
            return val instanceof Number ? ((Number) val).intValue() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private void logUsage(Long userId, Map<String, Object> response) {
        int promptTokens = extractTokenCount(response, "promptTokenCount");
        int completionTokens = extractTokenCount(response, "candidatesTokenCount");
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

    private static class SuggestRetryableException extends RuntimeException {
        SuggestRetryableException(String msg) { super(msg); }
    }

    private static class SuggestNonRetryableException extends RuntimeException {
        SuggestNonRetryableException(String msg) { super(msg); }
    }
}
