package com.tranbac.chiptripbe.module.trip.service.impl;

import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.trip.dto.request.CreateActivityRequest;
import com.tranbac.chiptripbe.module.trip.dto.request.ReorderActivitiesRequest;
import com.tranbac.chiptripbe.module.trip.dto.request.UpdateActivityRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.TripDetailResponse;
import com.tranbac.chiptripbe.module.trip.entity.Activity;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.trip.entity.TripDay;
import com.tranbac.chiptripbe.module.trip.repository.ActivityRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripDayRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.trip.service.ActivityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ActivityServiceImpl implements ActivityService {

    private final ActivityRepository activityRepository;
    private final TripDayRepository tripDayRepository;
    private final TripRepository tripRepository;

    @Override
    @Transactional
    public TripDetailResponse.ActivityDetail addActivity(Long userId, Long tripId, Long dayId, CreateActivityRequest request) {
        Trip trip = findTripAndValidateOwnership(tripId, userId);
        TripDay day = findDayAndValidate(dayId, trip);

        int maxOrder = day.getActivities().stream()
                .mapToInt(Activity::getDisplayOrder)
                .max().orElse(0);

        Activity activity = Activity.builder()
                .day(day)
                .startTime(request.getStartTime())
                .name(request.getName())
                .description(request.getDescription())
                .type(request.getType())
                .costVnd(request.getCostVnd() != null ? request.getCostVnd() : 0L)
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .imageUrl(request.getImageUrl())
                .bookingUrl(request.getBookingUrl())
                .displayOrder(request.getDisplayOrder() != null ? request.getDisplayOrder() : maxOrder + 1)
                .build();

        activity = activityRepository.save(activity);
        log.info("Added activity id={} to tripId={} dayId={}", activity.getId(), tripId, dayId);
        return toDetail(activity);
    }

    @Override
    @Transactional
    public TripDetailResponse.ActivityDetail updateActivity(Long userId, Long tripId, Long dayId,
                                                            Long activityId, UpdateActivityRequest request) {
        Trip trip = findTripAndValidateOwnership(tripId, userId);
        findDayAndValidate(dayId, trip);

        Activity activity = activityRepository.findByIdAndDayTripUserId(activityId, userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy hoạt động"));

        if (!activity.getDay().getId().equals(dayId)) {
            throw AppException.badRequest("Hoạt động không thuộc ngày này");
        }

        if (request.getStartTime() != null) activity.setStartTime(request.getStartTime());
        if (request.getName() != null) activity.setName(request.getName());
        if (request.getDescription() != null) activity.setDescription(request.getDescription());
        if (request.getType() != null) activity.setType(request.getType());
        if (request.getCostVnd() != null) activity.setCostVnd(request.getCostVnd());
        if (request.getLatitude() != null) activity.setLatitude(request.getLatitude());
        if (request.getLongitude() != null) activity.setLongitude(request.getLongitude());
        if (request.getImageUrl() != null) activity.setImageUrl(request.getImageUrl());
        if (request.getBookingUrl() != null) activity.setBookingUrl(request.getBookingUrl());
        if (request.getDisplayOrder() != null) activity.setDisplayOrder(request.getDisplayOrder());

        activity = activityRepository.save(activity);
        log.info("Updated activity id={}", activityId);
        return toDetail(activity);
    }

    @Override
    @Transactional
    public void deleteActivity(Long userId, Long tripId, Long dayId, Long activityId) {
        findTripAndValidateOwnership(tripId, userId);

        Activity activity = activityRepository.findByIdAndDayTripUserId(activityId, userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy hoạt động"));

        if (!activity.getDay().getId().equals(dayId)) {
            throw AppException.badRequest("Hoạt động không thuộc ngày này");
        }

        activityRepository.delete(activity);
        log.info("Deleted activity id={}", activityId);
    }

    @Override
    @Transactional
    public void reorderActivities(Long userId, Long tripId, Long dayId, ReorderActivitiesRequest request) {
        Trip trip = findTripAndValidateOwnership(tripId, userId);
        findDayAndValidate(dayId, trip);

        List<Activity> activities = activityRepository.findByDayIdOrderByDisplayOrder(dayId);
        for (int i = 0; i < request.getOrderedIds().size(); i++) {
            final int order = i + 1;
            final Long actId = request.getOrderedIds().get(i);
            activities.stream()
                    .filter(a -> a.getId().equals(actId))
                    .findFirst()
                    .ifPresent(a -> a.setDisplayOrder(order));
        }
        activityRepository.saveAll(activities);
        log.info("Reordered {} activities in dayId={}", request.getOrderedIds().size(), dayId);
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private Trip findTripAndValidateOwnership(Long tripId, Long userId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi"));
        if (!trip.getUser().getId().equals(userId)) {
            throw AppException.forbidden("Bạn không có quyền với chuyến đi này");
        }
        return trip;
    }

    private TripDay findDayAndValidate(Long dayId, Trip trip) {
        return tripDayRepository.findById(dayId)
                .filter(d -> d.getTrip().getId().equals(trip.getId()))
                .orElseThrow(() -> AppException.notFound("Không tìm thấy ngày trong chuyến đi"));
    }

    private TripDetailResponse.ActivityDetail toDetail(Activity activity) {
        return TripDetailResponse.ActivityDetail.builder()
                .id(activity.getId())
                .startTime(activity.getStartTime())
                .name(activity.getName())
                .description(activity.getDescription())
                .type(activity.getType())
                .costVnd(activity.getCostVnd())
                .latitude(activity.getLatitude())
                .longitude(activity.getLongitude())
                .imageUrl(activity.getImageUrl())
                .bookingUrl(activity.getBookingUrl())
                .displayOrder(activity.getDisplayOrder())
                .build();
    }
}
