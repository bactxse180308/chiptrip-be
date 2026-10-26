package com.tranbac.chiptripbe.module.trip.service.impl;

import com.tranbac.chiptripbe.common.enums.TripLifecycleStatus;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.place.entity.PlaceCache;
import com.tranbac.chiptripbe.module.place.repository.PlaceCacheRepository;
import com.tranbac.chiptripbe.module.trip.dto.response.ShareTokenResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripReadOnlyResponse;
import com.tranbac.chiptripbe.module.trip.entity.Activity;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.trip.entity.TripDay;
import com.tranbac.chiptripbe.module.trip.repository.ActivityRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripDayRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.trip.service.TripSharingService;
import com.tranbac.chiptripbe.module.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Controls public/shared access and constructs the privacy-safe read-only DTO. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class TripSharingServiceImpl implements TripSharingService {
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final TripRepository tripRepository;
    private final TripDayRepository tripDayRepository;
    private final ActivityRepository activityRepository;
    private final PlaceCacheRepository placeCacheRepository;
    private final TripActivityViewMapper tripActivityViewMapper;

    @Override
    @Transactional
    public ShareTokenResponse enableShare(Long userId, Long tripId) {
        Trip trip = findTripByIdAndUserId(tripId, userId);
        String token = TripShareTokens.create();
        trip.setShareToken(token);
        tripRepository.save(trip);
        log.info("Enabled share for trip id={}", tripId);
        return ShareTokenResponse.builder().shareToken(token).build();
    }

    @Override
    @Transactional
    public void disableShare(Long userId, Long tripId) {
        Trip trip = findTripByIdAndUserId(tripId, userId);
        trip.setShareToken(null);
        tripRepository.save(trip);
        log.info("Disabled share for trip id={}", tripId);
    }

    @Override
    @Transactional(readOnly = true)
    public TripReadOnlyResponse getSharedTrip(String shareToken) {
        if (!TripShareTokens.isCurrent(shareToken)) {
            throw AppException.notFound("Link chia sẻ đã hết hiệu lực. Chủ chuyến đi cần tạo link mới.");
        }
        Trip trip = tripRepository.findByShareToken(shareToken)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi được chia sẻ"));
        return buildReadOnlyResponse(trip, true);
    }

    @Override
    @Transactional(readOnly = true)
    public TripReadOnlyResponse getPublicTrip(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .filter(Trip::isPublic)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi"));
        return buildReadOnlyResponse(trip, false);
    }

    private TripReadOnlyResponse buildReadOnlyResponse(Trip trip, boolean shared) {
        List<TripDay> days = tripDayRepository.findByTripIdOrderByDayNumber(trip.getId());
        List<Activity> activities = activityRepository.findByTripIdWithDayOrderByDayAndOrder(trip.getId());
        Map<Long, List<Activity>> byDay = activities.stream()
                .collect(java.util.stream.Collectors.groupingBy(a -> a.getDay().getId()));
        List<Long> placeIds = activities.stream().map(Activity::getPlaceCacheId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, PlaceCache> places = new HashMap<>();
        if (!placeIds.isEmpty()) placeCacheRepository.findAllById(placeIds).forEach(p -> places.put(p.getId(), p));
        List<TripReadOnlyResponse.Day> resultDays = days.stream().map(day -> {
            List<TripReadOnlyResponse.Activity> resultActivities = byDay.getOrDefault(day.getId(), List.of()).stream()
                    .map(a -> tripActivityViewMapper.toDetail(a, places))
                    .map(a -> new TripReadOnlyResponse.Activity(a.getId(), a.getStartTime(), a.getName(), a.getDescription(),
                            a.getType(), a.getCostVnd(), a.getLatitude(), a.getLongitude(), a.getImageUrl(),
                            shared ? a.getBookingUrl() : null, a.getDisplayOrder(), a.getPlaceCacheId(), a.getAddress()))
                    .toList();
            long cost = resultActivities.stream().mapToLong(a -> a.costVnd() == null ? 0 : a.costVnd()).sum();
            return new TripReadOnlyResponse.Day(day.getId(), day.getDayNumber(), shared ? day.getDate() : null, cost, resultActivities);
        }).toList();
        User owner = trip.getUser();
        return new TripReadOnlyResponse(trip.getId(), trip.getTitle(), trip.getDeparture(), trip.getDestination(),
                shared ? trip.getDateStart() : null, shared ? trip.getDateEnd() : null,
                trip.getPeopleCount(), trip.getStyles(), resultDays.stream().mapToLong(TripReadOnlyResponse.Day::dayCostVnd).sum(),
                trip.isPublic(), trip.getLikesCount(), trip.getCommentsCount(),
                shared ? TripLifecycleStatus.of(trip.getDateStart(), trip.getDateEnd(), LocalDate.now(VN_ZONE)).name() : null,
                trip.isEnrichmentPending(), new TripReadOnlyResponse.UserInfo(owner.getId(), owner.getFullName(), owner.getAvatarUrl()), resultDays);
    }

    private Trip findTripByIdAndUserId(Long tripId, Long userId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi"));
        if (!trip.getUser().getId().equals(userId)) {
            throw AppException.forbidden("Bạn không có quyền với chuyến đi này");
        }
        return trip;
    }
}
