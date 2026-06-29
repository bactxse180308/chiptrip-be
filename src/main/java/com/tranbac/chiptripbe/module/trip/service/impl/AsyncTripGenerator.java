package com.tranbac.chiptripbe.module.trip.service.impl;

import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.trip.dto.request.GenerateTripRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.TripGenerateResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripGenerationResultDto;
import com.tranbac.chiptripbe.module.trip.service.TripService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Worker chạy sinh lịch trình ở luồng nền (sau khi controller đã validate đồng bộ), rồi đẩy kết quả
 * về đúng user qua WebSocket. Tách khỏi luồng HTTP để client không phải giữ 1 kết nối dài (~30-90s) —
 * vốn là nguyên nhân broken pipe khi proxy/người dùng ngắt giữa chừng.
 *
 * Trip vẫn được persist trong generateTrip (nguồn chân lý); WS chỉ là tín hiệu best-effort —
 * nếu push thất bại, user vẫn thấy chuyến trong "Chuyến đi của tôi".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncTripGenerator {

    private static final String DESTINATION = "/queue/trip-generation";

    private final TripService tripService;
    private final SimpMessagingTemplate messagingTemplate;

    /** Khóa per-user chống chạy 2 job generate đồng thời (tránh tạo trùng trip + trừ trùng credit). */
    private final Set<Long> inFlightUsers = ConcurrentHashMap.newKeySet();

    /** Đánh dấu user bắt đầu 1 job; ném 409 nếu đã có job đang chạy. Gọi ĐỒNG BỘ ở controller. */
    public void begin(Long userId) {
        if (!inFlightUsers.add(userId)) {
            throw AppException.conflict("Bạn đang có một lịch trình đang được tạo. Vui lòng đợi hoàn tất.");
        }
    }

    /** Nhả khóa khi không submit được job (vd executor từ chối ngay). */
    public void release(Long userId) {
        inFlightUsers.remove(userId);
    }

    @Async("tripGenerateExecutor")
    public void run(Long userId, GenerateTripRequest request) {
        try {
            TripGenerateResponse resp = tripService.generateTrip(userId, request);
            push(userId, TripGenerationResultDto.done(resp.getId(), resp.getGeocodeFailedCount()));
        } catch (AppException e) {
            // Lỗi nghiệp vụ đã có message thân thiện (hết lượt, AI lỗi, validate fail...)
            log.warn("Async generate failed for userId={}: {}", userId, e.getMessage());
            push(userId, TripGenerationResultDto.failed(e.getMessage()));
        } catch (Exception e) {
            // Biên worker: phải bắt mọi lỗi còn lại để báo FAILED, tránh để FE kẹt màn loading.
            log.error("Async generate crashed for userId={}", userId, e);
            push(userId, TripGenerationResultDto.failed(
                    "AI không thể tạo lịch trình lúc này. Vui lòng thử lại sau."));
        } finally {
            inFlightUsers.remove(userId);
        }
    }

    /** WS push best-effort — lỗi đẩy KHÔNG ảnh hưởng trip đã persist, chỉ log warn. */
    private void push(Long userId, TripGenerationResultDto dto) {
        try {
            messagingTemplate.convertAndSendToUser(String.valueOf(userId), DESTINATION, dto);
        } catch (MessagingException ex) {
            log.warn("Failed to push trip-generation result via WS to userId={}: {}",
                    userId, ex.getMessage());
        }
    }
}
