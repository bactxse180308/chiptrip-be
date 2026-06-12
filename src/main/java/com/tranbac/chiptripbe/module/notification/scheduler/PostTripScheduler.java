package com.tranbac.chiptripbe.module.notification.scheduler;

import com.tranbac.chiptripbe.module.notification.event.PostTripEvent;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Mỗi sáng 9:00 (giờ VN): tìm các chuyến VỪA kết thúc (dateEnd = hôm qua) và publish
 * PostTripEvent cho owner — nhắc đánh giá địa điểm & chia sẻ lịch trình lên feed.
 *
 * Query theo dateEnd = hôm qua nên mỗi chuyến chỉ bắn đúng 1 lần (không cần dedup).
 * Lưu ý scale nhiều instance: giống TripReminderScheduler, cần ShedLock. MVP 1 instance chưa cần.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostTripScheduler {

    private final TripRepository tripRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Ho_Chi_Minh")
    public void sendPostTripPrompts() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        List<Trip> trips = tripRepository.findByDateEnd(yesterday);
        log.info("PostTripScheduler: {} chuyến kết thúc ngày {}", trips.size(), yesterday);
        for (Trip trip : trips) {
            eventPublisher.publishEvent(new PostTripEvent(
                    trip.getUser().getId(),
                    trip.getId(),
                    trip.getTitle()
            ));
        }
    }
}
