package com.tranbac.chiptripbe.module.notification.scheduler;

import com.tranbac.chiptripbe.module.external.dto.response.WeatherResponse;
import com.tranbac.chiptripbe.module.external.service.ExternalApiService;
import com.tranbac.chiptripbe.module.notification.enums.NotificationType;
import com.tranbac.chiptripbe.module.notification.event.WeatherAlertEvent;
import com.tranbac.chiptripbe.module.notification.repository.NotificationRepository;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Mỗi sáng 7:00 (VN): với các trip khởi hành trong N ngày tới, kiểm tra dự báo
 * thời tiết và publish WeatherAlertEvent cho các ngày có thời tiết xấu.
 *
 * Chống duplicate: trước khi publish, kiểm tra đã có notification WEATHER_ALERT
 * với refId = tripId và body chứa ISO date đó chưa.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherAlertScheduler {

    /** Số ngày tới cần quét. Hợp lý vì OpenWeather free chỉ trả forecast 5 ngày. */
    private static final int FORECAST_HORIZON_DAYS = 5;

    /** Điều kiện coi là "thời tiết xấu" — match phần `weather.main` từ OpenWeather. */
    private static final Set<String> BAD_CONDITIONS = Set.of("Rain", "Thunderstorm", "Snow", "Tornado", "Squall");

    private final TripRepository tripRepository;
    private final ExternalApiService externalApiService;
    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Ho_Chi_Minh")
    public void scanForWeatherAlerts() {
        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(FORECAST_HORIZON_DAYS);
        List<Trip> upcoming = tripRepository.findByDateStartBetween(today, horizon);
        log.info("WeatherAlertScheduler: kiểm tra {} chuyến trong {} ngày tới", upcoming.size(), FORECAST_HORIZON_DAYS);

        for (Trip trip : upcoming) {
            checkTrip(trip);
        }
    }

    private void checkTrip(Trip trip) {
        WeatherResponse weather = externalApiService.getWeather(
                trip.getDestination(), trip.getDateStart(), trip.getDateEnd());
        if (weather == null || weather.getForecasts() == null) return;

        for (WeatherResponse.DayForecast f : weather.getForecasts()) {
            if (f.getDate() == null || f.getCondition() == null) continue;
            if (!BAD_CONDITIONS.contains(f.getCondition())) continue;
            // Forecast nằm trong khoảng chuyến đi
            if (f.getDate().isBefore(trip.getDateStart()) || f.getDate().isAfter(trip.getDateEnd())) continue;

            Long userId = trip.getUser().getId();
            String dateIso = f.getDate().toString();
            // Dedup: chỉ alert 1 lần / trip + ngày
            boolean exists = notificationRepository.existsByRecipientIdAndTypeAndRefIdAndBodyContaining(
                    userId, NotificationType.WEATHER_ALERT, trip.getId(), dateIso);
            if (exists) continue;

            eventPublisher.publishEvent(new WeatherAlertEvent(
                    userId, trip.getId(), trip.getTitle(),
                    f.getDate(), f.getCondition(), f.getDescription()
            ));
        }
    }
}
