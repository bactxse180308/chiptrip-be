package com.tranbac.chiptripbe.module.ai.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.common.config.AiProperties;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.ai.dto.AiCallResult;
import com.tranbac.chiptripbe.module.ai.dto.AiItineraryResult;
import com.tranbac.chiptripbe.module.ai.service.AiService;
import com.tranbac.chiptripbe.module.trip.dto.request.GenerateTripRequest;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
class AiGenerateServiceImpl implements AiService {

    private final WebClient.Builder webClientBuilder;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

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
    public AiCallResult generateItinerary(GenerateTripRequest request) {
        Map<String, Object> requestBody = buildGeminiRequest(request);

        for (int attempt = 0; attempt <= aiProperties.getMaxRetries(); attempt++) {
            try {
                Map<String, Object> response = callGemini(requestBody);
                String content = extractContent(response);
                AiItineraryResult itinerary = parseAndValidate(content);
                int promptTokens = extractTokenCount(response, "promptTokenCount");
                int completionTokens = extractTokenCount(response, "candidatesTokenCount");
                return new AiCallResult(itinerary, promptTokens, completionTokens);

            } catch (AiNonRetryableException e) {
                log.error("Non-retryable AI error: {}", e.getMessage());
                throw AppException.badRequest("Không thể tạo lịch trình: " + e.getMessage());

            } catch (Exception e) {
                if (attempt == aiProperties.getMaxRetries()) {
                    log.error("AI generation failed after {} attempts", attempt + 1, e);
                    throw AppException.internal("AI không thể tạo lịch trình lúc này. Vui lòng thử lại sau.");
                }
                log.warn("AI attempt {}/{} failed, retrying: {}", attempt + 1, aiProperties.getMaxRetries() + 1, e.getMessage());
            }
        }

        throw AppException.internal("AI generation failed");
    }

    // ─── Build request ────────────────────────────────────────────────────────

    private Map<String, Object> buildGeminiRequest(GenerateTripRequest request) {
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

        Map<String, Object> generationConfig = Map.of(
                "responseMimeType", "application/json",
                "responseSchema", buildResponseSchema(),
                // thinkingBudget=0 tắt chain-of-thought reasoning của gemini-2.5-flash,
                // tránh timeout do thinking có thể mất 2-5 phút với prompt phức tạp
                "thinkingConfig", Map.of("thinkingBudget", 0)
        );

        return Map.of(
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt)))),
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                "generationConfig", generationConfig
        );
    }

    private Map<String, Object> buildResponseSchema() {
        // Gemini schema uses uppercase type names: STRING, INTEGER, OBJECT, ARRAY
        Map<String, Object> activityProps = new LinkedHashMap<>();
        activityProps.put("time", Map.of("type", "STRING", "description", "Giờ theo định dạng HH:mm"));
        activityProps.put("name", Map.of("type", "STRING"));
        activityProps.put("description", Map.of("type", "STRING"));
        activityProps.put("type", Map.of("type", "STRING",
                "enum", List.of("FOOD", "ATTRACTION", "TRANSPORT", "ACCOMMODATION", "OTHER")));
        activityProps.put("costVnd", Map.of("type", "INTEGER"));
        activityProps.put("searchQuery", Map.of("type", "STRING",
                "description", "Tên địa điểm cụ thể + tỉnh/thành. VD: 'Bánh căn Nhà Chung Đà Lạt'"));
        activityProps.put("bookingUrl", Map.of("type", "STRING", "nullable", true));

        Map<String, Object> activitySchema = new LinkedHashMap<>();
        activitySchema.put("type", "OBJECT");
        activitySchema.put("properties", activityProps);
        activitySchema.put("required", List.of("time", "name", "description", "type", "costVnd", "searchQuery"));

        Map<String, Object> dayProps = new LinkedHashMap<>();
        dayProps.put("dayNumber", Map.of("type", "INTEGER"));
        dayProps.put("date", Map.of("type", "STRING", "description", "Ngày theo định dạng yyyy-MM-dd"));
        dayProps.put("activities", Map.of("type", "ARRAY", "items", activitySchema));

        Map<String, Object> daySchema = new LinkedHashMap<>();
        daySchema.put("type", "OBJECT");
        daySchema.put("properties", dayProps);
        daySchema.put("required", List.of("dayNumber", "date", "activities"));

        Map<String, Object> checklistProps = new LinkedHashMap<>();
        checklistProps.put("category", Map.of("type", "STRING",
                "enum", List.of("PAPERS", "CLOTHES", "HYGIENE", "OTHER")));
        checklistProps.put("name", Map.of("type", "STRING"));

        Map<String, Object> checklistItemSchema = new LinkedHashMap<>();
        checklistItemSchema.put("type", "OBJECT");
        checklistItemSchema.put("properties", checklistProps);
        checklistItemSchema.put("required", List.of("category", "name"));

        Map<String, Object> rootProps = new LinkedHashMap<>();
        rootProps.put("title", Map.of("type", "STRING"));
        rootProps.put("days", Map.of("type", "ARRAY", "items", daySchema));
        rootProps.put("checklist", Map.of("type", "ARRAY", "items", checklistItemSchema));

        Map<String, Object> rootSchema = new LinkedHashMap<>();
        rootSchema.put("type", "OBJECT");
        rootSchema.put("properties", rootProps);
        rootSchema.put("required", List.of("title", "days", "checklist"));

        return rootSchema;
    }

    // ─── HTTP call ────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> callGemini(Map<String, Object> body) {
        String model = aiProperties.getGemini().getModel();
        return geminiClient.post()
                .uri("/models/" + model + ":generateContent")
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.value() == 400 || status.value() == 401 || status.value() == 403,
                        response -> response.bodyToMono(String.class)
                                .map(errorBody -> (Throwable) new AiNonRetryableException(
                                        "Gemini HTTP " + response.statusCode().value() + ": " + errorBody)))
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .map(errorBody -> (Throwable) new AiRetryableException(
                                        "Gemini HTTP " + response.statusCode().value())))
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(aiProperties.getTimeoutSeconds()))
                .onErrorMap(WebClientRequestException.class,
                        e -> new AiRetryableException("Connection error: " + e.getMessage()))
                .block();
    }

    // ─── Parse & validate ─────────────────────────────────────────────────────

    private AiItineraryResult parseAndValidate(String content) {
        AiItineraryResult result;
        try {
            result = objectMapper.readValue(content, AiItineraryResult.class);
        } catch (JsonProcessingException e) {
            throw new AiRetryableException("JSON parse failed: " + e.getMessage());
        }

        if (result.getDays() == null || result.getDays().isEmpty()) {
            throw new AiRetryableException("AI trả về lịch trình rỗng");
        }
        for (AiItineraryResult.AiDay day : result.getDays()) {
            if (day.getDayNumber() == null || day.getDate() == null) {
                throw new AiRetryableException("AiDay thiếu dayNumber hoặc date");
            }
            if (day.getActivities() == null || day.getActivities().isEmpty()) {
                throw new AiRetryableException("Ngày " + day.getDayNumber() + " không có hoạt động");
            }
            for (AiItineraryResult.AiActivity activity : day.getActivities()) {
                if (activity.getTime() == null || activity.getName() == null) {
                    throw new AiRetryableException("Activity thiếu time hoặc name");
                }
                if (activity.getCostVnd() != null && activity.getCostVnd() < 0) {
                    activity.setCostVnd(0L);
                }
                boolean needsGeocode = isGeocodableType(activity.getType());
                if (needsGeocode && (activity.getSearchQuery() == null || activity.getSearchQuery().isBlank())) {
                    throw new AiRetryableException(
                            "Activity '" + activity.getName() + "' (type=" + activity.getType() + ") thiếu searchQuery");
                }
            }
        }

        return result;
    }

    // ─── Response helpers ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> response) {
        if (response == null) throw new AiRetryableException("Gemini trả về response null");
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
        if (candidates == null || candidates.isEmpty()) throw new AiRetryableException("Gemini trả về candidates rỗng");
        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        if (content == null) throw new AiRetryableException("Gemini trả về content null");
        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
        if (parts == null || parts.isEmpty()) throw new AiRetryableException("Gemini trả về parts rỗng");
        String text = (String) parts.get(0).get("text");
        if (text == null || text.isBlank()) throw new AiRetryableException("Gemini trả về text rỗng");
        return text;
    }

    @SuppressWarnings("unchecked")
    private int extractTokenCount(Map<String, Object> response, String field) {
        try {
            Map<String, Object> usageMetadata = (Map<String, Object>) response.get("usageMetadata");
            Object val = usageMetadata.get(field);
            return val instanceof Number ? ((Number) val).intValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** FOOD, ATTRACTION, ACCOMMODATION, TRANSPORT đều cần searchQuery để geocode. OTHER bỏ qua. */
    static boolean isGeocodableType(String type) {
        return "FOOD".equals(type) || "ATTRACTION".equals(type)
                || "ACCOMMODATION".equals(type) || "TRANSPORT".equals(type);
    }

    // ─── Internal exception types ─────────────────────────────────────────────

    private static class AiRetryableException extends RuntimeException {
        AiRetryableException(String msg) { super(msg); }
    }

    private static class AiNonRetryableException extends RuntimeException {
        AiNonRetryableException(String msg) { super(msg); }
    }
}