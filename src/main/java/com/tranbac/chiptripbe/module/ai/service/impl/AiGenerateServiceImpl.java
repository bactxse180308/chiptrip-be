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
    public AiCallResult generateItinerary(GenerateTripRequest request, String userPreferences) {
        Map<String, Object> requestBody = buildRequest(request, userPreferences);

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

    private Map<String, Object> buildRequest(GenerateTripRequest request, String userPreferences) {
        String systemPrompt = """
                Bạn là chuyên gia du lịch Việt Nam.
                Quy tắc bắt buộc:
                - Chỉ trả về JSON hợp lệ, không markdown, không giải thích ngoài JSON.
                - Tên địa điểm phải có thật tại Việt Nam.
                - Chi phí tính bằng VNĐ nguyên (integer), không dấu phẩy, không chữ "VNĐ".
                - KHÔNG tự sinh latitude/longitude. Backend sẽ geocode từ searchQuery.
                - Mỗi activity phải có searchQuery rõ ràng để backend tìm tọa độ.
                  searchQuery phải là câu tìm kiếm bản đồ, gồm tên địa điểm cụ thể + quận/huyện hoặc tỉnh/thành phố.
                  KHÔNG đưa động từ/ngữ cảnh lịch trình vào searchQuery như "ăn tối tại", "tham quan", "đi", "check-in".
                  Dùng tên vùng dễ match với bản đồ: "Hồ Chí Minh" thay vì "Thành phố Hồ Chí Minh", "Đà Nẵng" thay vì "Thành phố Đà Nẵng".
                  Ví dụ đúng: "Bánh căn Nhà Chung Đà Lạt", "Hồ Xuân Hương Đà Lạt", "Thảo Cầm Viên Sài Gòn Hồ Chí Minh".
                  Ví dụ sai: "Ăn tối tại nhà hàng địa phương", "Tham quan khu vui chơi", "Khách sạn trung tâm" (quá chung, không tìm được).
                - TRANSPORT searchQuery phải thuộc đúng thành phố nơi phương tiện xuất phát/đến, KHÔNG gộp 2 tỉnh/thành vào cùng 1 query:
                  Ví dụ đúng: "Sân bay Quốc tế Đà Nẵng Đà Nẵng" (xuất phát Đà Nẵng), "Sân bay Liên Khương Đà Lạt" (đến Đà Lạt).
                  Ví dụ sai: "Sân bay Quốc tế Đà Nẵng Đà Lạt" (sai — gộp tên 2 tỉnh thành 1 query).
                - ACCOMMODATION searchQuery phải là tên khách sạn/homestay CỤ THỂ + tên tỉnh/thành phố, không sinh query chung:
                  Ví dụ đúng: "Ana Mandara Villas Đà Lạt", "Homestay Hoa Lan Đà Lạt".
                  Ví dụ sai: "Khách sạn trung tâm", "Homestay gần chợ" (quá chung, không geocode được).
                - Phân bổ chi phí hợp lý, tổng không vượt ngân sách.
                - costVnd của mỗi hoạt động là TỔNG chi phí khoản đó cho CẢ NHÓM (mọi thành viên), không phải chi phí mỗi người.
                - type phải là một trong: FOOD, ATTRACTION, TRANSPORT, ACCOMMODATION, OTHER.
                - category trong checklist phải là một trong: PAPERS, CLOTHES, HYGIENE, ELECTRONICS, MEDICINE, OTHER.
                - title BẮT BUỘC chứa đúng tên điểm đến và số ngày của chuyến đi.
                  Ví dụ đúng: "Hành trình Đà Lạt 3 ngày 2 đêm".
                - Mỗi ngày nên có 5-7 hoạt động, không nhồi quá nhiều, để lịch trình thực tế và khả thi.
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
                    { "category": "PAPERS|CLOTHES|HYGIENE|ELECTRONICS|MEDICINE|OTHER", "name": "string" }
                  ]
                }
                """;

        long numDays = java.time.temporal.ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
        String stylesText = formatStyleTags(request.getStyles());
        int peopleCount = (request.getPeopleCount() != null && request.getPeopleCount() > 0)
                ? request.getPeopleCount() : 1;
        long budgetPerPerson = request.getBudgetVnd() / peopleCount;

        // #6: du lịch tại chỗ (điểm khởi hành trùng điểm đến) thì KHÔNG yêu cầu chặng di chuyển liên tỉnh
        String firstDayLine = isSameLocation(request.getDeparture(), request.getDestination())
                ? String.format("- Du lịch tại chỗ ở %s: KHÔNG thêm chặng di chuyển liên tỉnh, tập trung khám phá trong %s.",
                        request.getDestination(), request.getDestination())
                : String.format("- Ngày đầu bao gồm di chuyển từ %s đến %s (TRANSPORT).",
                        request.getDeparture(), request.getDestination());

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
                - title phải chứa tên điểm đến "%s" và số ngày (%d ngày).
                - Mỗi ngày 5-7 hoạt động từ sáng đến tối.
                %s
                - Ít nhất 2 ATTRACTION mỗi ngày.
                - Trước khi trả JSON, tự kiểm tra mọi searchQuery của FOOD/ATTRACTION/ACCOMMODATION/TRANSPORT:
                  phải là tên địa điểm có thể copy vào Google Maps/Goong để tìm đúng ngay, không phải câu mô tả hoạt động.
                - Bao gồm checklist chuẩn bị đồ cho chuyến đi (%d người, %d ngày), tối thiểu 12 mục, không trùng tên.
                - Checklist phải có nhóm cơ bản: giấy tờ, quần áo theo số ngày, vệ sinh cá nhân, sạc/pin dự phòng, thuốc/y tế.
                - Checklist phải bám Phong cách và điểm đến:
                  + beach/resort/biển/đảo/lặn biển: thêm đồ bơi, khăn biển, túi chống nước, kính râm hoặc kính bơi, kem chống nắng.
                  + mountain/adventure/núi/trekking/cắm trại/săn mây: thêm giày trekking, áo khoác gió hoặc áo mưa, thuốc chống côn trùng, balo nhỏ.
                  + photo/check-in/sống ảo: thêm máy ảnh/gimbal/tripod, outfit chụp ảnh, pin dự phòng.
                  + food/food_tour: thêm men tiêu hóa hoặc thuốc đau bụng.
                  + family/trẻ em: thêm giấy tờ trẻ em, thuốc cơ bản, khăn ướt.
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
                request.getDestination(),
                numDays,
                firstDayLine,
                peopleCount,
                numDays
        );

        // Cá nhân hóa theo gu du lịch đã lưu trong hồ sơ (User.preferences) —
        // chỉ là tín hiệu ưu tiên, KHÔNG ghi đè "Phong cách" user chọn cho chuyến này
        String preferencesText = formatPreferences(userPreferences);
        if (preferencesText != null) {
            userPrompt += String.format("""

                    Gu du lịch đã lưu trong hồ sơ của khách (ưu tiên cá nhân hóa hoạt động theo gu này
                    khi không mâu thuẫn với Phong cách ở trên): %s
                    """, preferencesText);
        }

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

    /**
     * Đổi style user chọn khi generate trip thành mô tả tiếng Việt để AI hiểu đúng
     * thay vì chỉ thấy tag kỹ thuật như "photo", "resort", "family".
     */
    private String formatStyleTags(List<String> styles) {
        if (styles == null || styles.isEmpty()) return "không có yêu cầu đặc biệt";
        Map<String, String> labels = Map.ofEntries(
                Map.entry("healing", "nghỉ dưỡng, chữa lành, thư giãn"),
                Map.entry("food", "food tour, ăn đặc sản địa phương"),
                Map.entry("food_tour", "food tour, ăn đặc sản địa phương"),
                Map.entry("photo", "check-in sống ảo, địa điểm chụp ảnh đẹp"),
                Map.entry("checkin", "check-in sống ảo, địa điểm chụp ảnh đẹp"),
                Map.entry("check-in", "check-in sống ảo, địa điểm chụp ảnh đẹp"),
                Map.entry("adventure", "mạo hiểm, leo núi, trekking, lặn biển"),
                Map.entry("beach", "đi biển, đảo, tắm biển, lặn ngắm san hô"),
                Map.entry("mountain", "đi núi, trekking, săn mây, cắm trại"),
                Map.entry("resort", "resort, chill, hồ bơi, view biển"),
                Map.entry("family", "gia đình, an toàn, có trẻ em"),
                Map.entry("couple", "couple, hẹn hò, lãng mạn, riêng tư"),
                Map.entry("nightlife", "nightlife, bar, club, phố đêm"),
                Map.entry("culture", "văn hóa, lịch sử, di tích, bảo tàng"),
                Map.entry("local", "trải nghiệm local, sống như người địa phương"),
                Map.entry("luxury", "sang chảnh, khách sạn tốt, fine dining"),
                Map.entry("group", "bạn bè hoặc nhóm, vui, sôi động")
        );
        String text = styles.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(tag -> labels.getOrDefault(tag.toLowerCase(), tag))
                .distinct()
                .collect(java.util.stream.Collectors.joining("; "));
        return text.isEmpty() ? "không có yêu cầu đặc biệt" : text;
    }

    /**
     * Đổi tag preferences ("healing,food,photo,adventure" — lưu từ Profile FE)
     * thành mô tả tiếng Việt cho prompt. Tag lạ giữ nguyên. Trả null nếu trống.
     */
    private String formatPreferences(String userPreferences) {
        if (userPreferences == null || userPreferences.isBlank()) return null;
        Map<String, String> labels = Map.of(
                "healing", "chữa lành, thư giãn nhẹ nhàng",
                "food", "ẩm thực, ăn uống đặc sản",
                "photo", "sống ảo, địa điểm chụp ảnh đẹp",
                "adventure", "mạo hiểm, trải nghiệm cảm giác mạnh",
                "beach", "đi biển, đảo, tắm biển, lặn ngắm san hô",
                "mountain", "đi núi, trekking, săn mây"
        );
        String text = java.util.Arrays.stream(userPreferences.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(tag -> labels.getOrDefault(tag, tag))
                .collect(java.util.stream.Collectors.joining("; "));
        return text.isEmpty() ? null : text;
    }

    /** #6: coi là "du lịch tại chỗ" khi điểm khởi hành và điểm đến là cùng một nơi. */
    private static boolean isSameLocation(String departure, String destination) {
        if (departure == null || destination == null) return false;
        String d = canonLocation(departure);
        return !d.isBlank() && d.equals(canonLocation(destination));
    }

    private static String canonLocation(String s) {
        return s.toLowerCase()
                .replace("thành phố", " ")
                .replace("tỉnh", " ")
                .replace("tp.", " ")
                .replace("tp ", " ")
                .replaceAll("\\s+", " ")
                .trim();
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
            return objectMapper.readValue(stripCodeFence(content), AiItineraryResult.class);
        } catch (JsonProcessingException e) {
            throw new AiRetryableException("JSON parse failed: " + e.getMessage());
        }
    }

    /** Phòng khi model bọc JSON trong ```json ... ``` dù prompt đã yêu cầu JSON thuần. */
    private static String stripCodeFence(String content) {
        String t = content.trim();
        if (!t.startsWith("```")) return t;
        int firstNewline = t.indexOf('\n');
        if (firstNewline >= 0) t = t.substring(firstNewline + 1);
        if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        return t.trim();
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
