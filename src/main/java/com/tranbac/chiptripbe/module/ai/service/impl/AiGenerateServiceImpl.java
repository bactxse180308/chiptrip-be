package com.tranbac.chiptripbe.module.ai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.common.config.AiProperties;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.ai.dto.AiCallResult;
import com.tranbac.chiptripbe.module.ai.dto.AiItineraryResult;
import com.tranbac.chiptripbe.module.ai.service.AiItineraryValidator;
import com.tranbac.chiptripbe.module.ai.service.AiService;
import com.tranbac.chiptripbe.module.trip.dto.request.GenerateTripRequest;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
class AiGenerateServiceImpl implements AiService {

    private final WebClient.Builder webClientBuilder;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final AiItineraryValidator aiItineraryValidator;

    private WebClient aiApiClient;

    @PostConstruct
    void init() {
        aiApiClient = webClientBuilder
                .baseUrl(aiProperties.getOpenaiCompat().getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + aiProperties.getOpenaiCompat().getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public AiCallResult generateItinerary(GenerateTripRequest request) {
        Map<String, Object> requestBody = buildRequest(request);

        for (int attempt = 0; attempt <= aiProperties.getMaxRetries(); attempt++) {
            try {
                Map<String, Object> response = callLlm(requestBody);
                String content = extractContent(response);
                AiItineraryResult itinerary = parseJson(content);
                // Business validation — coi như retryable: nếu AI trả số ngày lệch / searchQuery generic,
                // retry để AI tự sửa thay vì fail ngay (user không phải bấm lại)
                try {
                    aiItineraryValidator.validate(itinerary, request);
                } catch (AppException ae) {
                    throw new AiRetryableException("Itinerary invalid: " + ae.getMessage());
                }
                int[] tokens = extractTokenCount(response);
                return new AiCallResult(itinerary, tokens[0], tokens[1]);

            } catch (AiNonRetryableException e) {
                log.error("Non-retryable AI error: {}", e.getMessage());
                throw AppException.badRequest("Không thể tạo lịch trình: " + e.getMessage());

            } catch (AiRetryableException | WebClientException e) {
                if (attempt == aiProperties.getMaxRetries()) {
                    log.error("AI generation failed after {} attempts", attempt + 1, e);
                    throw AppException.internal("AI không thể tạo lịch trình lúc này. Vui lòng thử lại sau.");
                }
                log.warn("AI attempt {}/{} failed, retrying: {}", attempt + 1, aiProperties.getMaxRetries() + 1, e.getMessage());
                sleepBackoff(attempt);
            }
        }

        throw AppException.internal("AI generation failed");
    }

    /** Exponential backoff giữa các lần retry: 500ms, 1s, 2s, ... */
    private void sleepBackoff(int attempt) {
        try {
            long backoffMs = (long) (Math.pow(2, attempt) * 500);
            Thread.sleep(backoffMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw AppException.internal("Tạo lịch trình bị gián đoạn");
        }
    }

    // ─── Build request (OpenAI Chat Completions format) ───────────────────────

    private Map<String, Object> buildRequest(GenerateTripRequest request) {
        String systemPrompt = """
                Bạn là chuyên gia du lịch Việt Nam.
                Quy tắc bắt buộc:
                - Chỉ trả về JSON hợp lệ, không markdown, không giải thích ngoài JSON.
                - Tên địa điểm phải có thật tại Việt Nam.
                - Chi phí tính bằng VNĐ nguyên (integer), không dấu phẩy, không chữ "VNĐ".
                - KHÔNG tự sinh latitude/longitude. Backend sẽ geocode từ searchQuery.
                - Mỗi activity phải có searchQuery rõ ràng để backend tìm tọa độ.
                  searchQuery phải gồm tên địa điểm cụ thể + tên tỉnh/thành phố.
                  Ví dụ đúng: "Bánh căn Nhà Chung Đà Lạt", "Hồ Xuân Hương Đà Lạt", "Sân bay Nội Bài Hà Nội".
                  Ví dụ sai: "Nhà hàng địa phương", "Khu vui chơi" (quá chung, không tìm được).
                - TRANSPORT searchQuery phải thuộc đúng thành phố nơi phương tiện xuất phát/đến, KHÔNG gộp 2 tỉnh/thành vào cùng 1 query:
                  Ví dụ đúng: "Sân bay Quốc tế Đà Nẵng Đà Nẵng" (xuất phát Đà Nẵng), "Sân bay Liên Khương Đà Lạt" (đến Đà Lạt).
                  Ví dụ sai: "Sân bay Quốc tế Đà Nẵng Đà Lạt" (sai — gộp tên 2 tỉnh thành 1 query).
                - ACCOMMODATION searchQuery phải là tên khách sạn/homestay CỤ THỂ + tên tỉnh/thành phố, không sinh query chung:
                  Ví dụ đúng: "Ana Mandara Villas Đà Lạt", "Homestay Hoa Lan Đà Lạt".
                  Ví dụ sai: "Khách sạn trung tâm", "Homestay gần chợ" (quá chung, không geocode được).
                - Phân bổ chi phí hợp lý, tổng không vượt ngân sách.
                - type phải là một trong: FOOD, ATTRACTION, TRANSPORT, ACCOMMODATION, OTHER.
                - category trong checklist phải là một trong: PAPERS, CLOTHES, HYGIENE, OTHER.
                - title phải nhắc đến điểm đến và số ngày của chuyến đi.
                  Ví dụ đúng: "Hành trình Đà Lạt 3 ngày 2 đêm".
                - Mỗi ngày nên có 4-6 hoạt động, không nhồi quá nhiều, để lịch trình thực tế và khả thi.
                - description ngắn gọn, 1-2 câu, đi thẳng vào nội dung.
                - bookingUrl để null nếu không chắc chắn link có thật. KHÔNG bịa URL.
                - Với type OTHER không phải địa điểm cụ thể (vd "nghỉ ngơi", "tự do"), searchQuery có thể để null.

                Trả về JSON đúng cấu trúc sau (root là object):
                {
                  "title": "string",
                  "days": [
                    {
                      "dayNumber": 1,
                      "date": "yyyy-MM-dd",
                      "activities": [
                        {
                          "time": "HH:mm",
                          "name": "string",
                          "description": "string",
                          "type": "FOOD|ATTRACTION|TRANSPORT|ACCOMMODATION|OTHER",
                          "costVnd": 350000,
                          "searchQuery": "Tên cụ thể + tỉnh/thành",
                          "bookingUrl": "string hoặc null"
                        }
                      ]
                    }
                  ],
                  "checklist": [
                    { "category": "PAPERS|CLOTHES|HYGIENE|OTHER", "name": "string" }
                  ]
                }
                """;

        long numDays = java.time.temporal.ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
        String stylesText = (request.getStyles() != null && !request.getStyles().isEmpty())
                ? String.join(", ", request.getStyles())
                : "không có yêu cầu đặc biệt";
        int peopleCount = (request.getPeopleCount() != null && request.getPeopleCount() > 0)
                ? request.getPeopleCount() : 1;
        long budgetPerPerson = request.getBudgetVnd() / peopleCount;

        String userPrompt = String.format("""
                Tạo lịch trình du lịch với thông tin sau:
                - Điểm khởi hành: %s
                - Điểm đến: %s
                - Ngày bắt đầu: %s
                - Ngày kết thúc: %s
                - Số ngày: %d
                - Số người: %d
                - Ngân sách tổng: %,d VNĐ (%,d VNĐ/người)
                - Phong cách: %s

                Yêu cầu:
                - Mỗi ngày 5-7 hoạt động từ sáng đến tối.
                - Ngày đầu bao gồm di chuyển từ %s đến %s (TRANSPORT).
                - Ít nhất 2 ATTRACTION mỗi ngày.
                - Bao gồm checklist chuẩn bị đồ cho chuyến đi (%d người, %d ngày).
                """,
                request.getDeparture(),
                request.getDestination(),
                request.getStartDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
                request.getEndDate().format(DateTimeFormatter.ISO_LOCAL_DATE),
                numDays,
                peopleCount,
                request.getBudgetVnd(),
                budgetPerPerson,
                stylesText,
                request.getDeparture(),
                request.getDestination(),
                peopleCount,
                numDays
        );

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

    // ─── HTTP call ────────────────────────────────────────────────────────────

    private Map<String, Object> callLlm(Map<String, Object> body) {
        return aiApiClient.post()
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.value() == 400 || status.value() == 401 || status.value() == 403,
                        response -> response.bodyToMono(String.class)
                                .map(errorBody -> (Throwable) new AiNonRetryableException(
                                        "AI HTTP " + response.statusCode().value() + ": " + errorBody)))
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .map(errorBody -> (Throwable) new AiRetryableException(
                                        "AI HTTP " + response.statusCode().value() + ": " + errorBody)))
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(aiProperties.getTimeoutSeconds()))
                .onErrorMap(TimeoutException.class,
                        e -> new AiRetryableException("AI timeout sau " + aiProperties.getTimeoutSeconds() + "s"))
                .onErrorMap(WebClientRequestException.class,
                        e -> new AiRetryableException("Connection error: " + e.getMessage()))
                .block();
    }

    // ─── Parse JSON (business validation đã chuyển sang AiItineraryValidator) ─

    private AiItineraryResult parseJson(String content) {
        try {
            return objectMapper.readValue(content, AiItineraryResult.class);
        } catch (JsonProcessingException e) {
            throw new AiRetryableException("JSON parse failed: " + e.getMessage());
        }
    }

    // ─── Response helpers (OpenAI Chat Completions format) ────────────────────

    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> response) {
        if (response == null) throw new AiRetryableException("AI trả về response null");
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty()) throw new AiRetryableException("AI trả về choices rỗng");
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        if (message == null) throw new AiRetryableException("AI trả về message null");
        String content = (String) message.get("content");
        if (content == null || content.isBlank()) throw new AiRetryableException("AI trả về content rỗng");
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

    // ─── Internal exception types ─────────────────────────────────────────────

    static class AiRetryableException extends RuntimeException {
        AiRetryableException(String msg) { super(msg); }
    }

    static class AiNonRetryableException extends RuntimeException {
        AiNonRetryableException(String msg) { super(msg); }
    }
}
