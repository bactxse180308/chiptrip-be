package com.tranbac.chiptripbe.module.trip.service.impl;

import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.trip.dto.response.TripDetailResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripSummaryResponse;
import com.tranbac.chiptripbe.module.trip.entity.Activity;
import com.tranbac.chiptripbe.module.trip.entity.ChecklistItem;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.trip.entity.TripDay;
import com.tranbac.chiptripbe.module.trip.repository.ActivityRepository;
import com.tranbac.chiptripbe.module.trip.repository.ChecklistItemRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripDayRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.trip.service.AdminTripService;
import com.tranbac.chiptripbe.module.trip.specification.TripSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class AdminTripServiceImpl implements AdminTripService {

    private final TripRepository tripRepository;
    private final TripDayRepository tripDayRepository;
    private final ActivityRepository activityRepository;
    private final ChecklistItemRepository checklistItemRepository;

    @Override
    public Page<TripSummaryResponse> getAllTrips(Long userId, LocalDate from, LocalDate to, Pageable pageable) {
        Specification<Trip> spec = Specification.allOf();
        if (userId != null) {
            spec = spec.and(TripSpecification.withUserId(userId));
        }
        if (from != null) {
            spec = spec.and(TripSpecification.createdAfter(LocalDateTime.of(from, LocalTime.MIN)));
        }
        if (to != null) {
            spec = spec.and(TripSpecification.createdBefore(LocalDateTime.of(to, LocalTime.MAX)));
        }
        return tripRepository.findAll(spec, pageable).map(this::toSummary);
    }

    @Override
    public TripDetailResponse getTripDetail(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi"));

        List<TripDay> days = tripDayRepository.findByTripIdOrderByDayNumber(trip.getId());
        List<ChecklistItem> checklist = checklistItemRepository.findByTripIdOrderByDisplayOrder(trip.getId());

        List<TripDetailResponse.DayDetail> dayDetails = days.stream().map(day -> {
            List<Activity> activities = activityRepository.findByDayIdOrderByDisplayOrder(day.getId());
            return TripDetailResponse.DayDetail.builder()
                    .id(day.getId())
                    .dayNumber(day.getDayNumber())
                    .date(day.getDate())
                    .activities(activities.stream().map(a -> TripDetailResponse.ActivityDetail.builder()
                            .id(a.getId())
                            .startTime(a.getStartTime())
                            .name(a.getName())
                            .description(a.getDescription())
                            .type(a.getType())
                            .costVnd(a.getCostVnd())
                            .latitude(a.getLatitude())
                            .longitude(a.getLongitude())
                            .imageUrl(a.getImageUrl())
                            .bookingUrl(a.getBookingUrl())
                            .displayOrder(a.getDisplayOrder())
                            .build()).toList())
                    .build();
        }).toList();

        List<TripDetailResponse.ChecklistItemDetail> checklistDetails = checklist.stream()
                .map(c -> TripDetailResponse.ChecklistItemDetail.builder()
                        .id(c.getId())
                        .category(c.getCategory())
                        .name(c.getName())
                        .isChecked(c.getIsChecked())
                        .displayOrder(c.getDisplayOrder())
                        .build()).toList();

        return TripDetailResponse.builder()
                .id(trip.getId())
                .title(trip.getTitle())
                .departure(trip.getDeparture())
                .destination(trip.getDestination())
                .dateStart(trip.getDateStart())
                .dateEnd(trip.getDateEnd())
                .peopleCount(trip.getPeopleCount())
                .budgetVnd(trip.getBudgetVnd())
                .styles(trip.getStyles())
                .createdAt(trip.getCreatedAt())
                .updatedAt(trip.getUpdatedAt())
                .user(TripDetailResponse.UserInfo.builder()
                        .id(trip.getUser().getId())
                        .email(trip.getUser().getEmail())
                        .fullName(trip.getUser().getFullName())
                        .build())
                .days(dayDetails)
                .checklist(checklistDetails)
                .build();
    }

    @Override
    @Transactional
    public void deleteTrip(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi"));
        tripRepository.delete(trip);
        log.info("Admin deleted tripId={}", tripId);
    }

    private TripSummaryResponse toSummary(Trip trip) {
        return TripSummaryResponse.builder()
                .id(trip.getId())
                .userId(trip.getUser().getId())
                .userFullName(trip.getUser().getFullName())
                .userEmail(trip.getUser().getEmail())
                .title(trip.getTitle())
                .departure(trip.getDeparture())
                .destination(trip.getDestination())
                .dateStart(trip.getDateStart())
                .dateEnd(trip.getDateEnd())
                .peopleCount(trip.getPeopleCount())
                .budgetVnd(trip.getBudgetVnd())
                .styles(trip.getStyles())
                .createdAt(trip.getCreatedAt())
                .build();
    }
}
