package com.tranbac.chiptripbe.module.notification.scheduler;

import com.tranbac.chiptripbe.module.notification.event.TripReminderEvent;
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
 * Mỗi sáng 8:00 (giờ VN): tìm các chuyến đi khởi hành vào ngày mai và publish
 * TripReminderEvent cho từng owner. Listener sẽ tạo Notification + đẩy WS.
 *
 * Lưu ý vận hành: khi scale nhiều instance, @Scheduled chạy trên mọi instance
 * gây thông báo trùng → cần ShedLock (https://github.com/lukas-krecan/ShedLock).
 * MVP 1 instance chưa cần.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TripReminderScheduler {

    private final TripRepository tripRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Ho_Chi_Minh")
    public void sendTripReminders() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        List<Trip> trips = tripRepository.findByDateStart(tomorrow);
        log.info("TripReminderScheduler: {} trips khởi hành ngày {}", trips.size(), tomorrow);
        for (Trip trip : trips) {
            eventPublisher.publishEvent(new TripReminderEvent(
                    trip.getUser().getId(),
                    trip.getId(),
                    trip.getTitle(),
                    trip.getDateStart()
            ));
        }
    }
}
