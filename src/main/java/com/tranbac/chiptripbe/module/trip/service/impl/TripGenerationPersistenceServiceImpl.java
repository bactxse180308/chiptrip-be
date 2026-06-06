package com.tranbac.chiptripbe.module.trip.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.common.config.AiProperties;
import com.tranbac.chiptripbe.common.enums.ActivityType;
import com.tranbac.chiptripbe.common.enums.ChecklistCategory;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.ai.dto.AiCallResult;
import com.tranbac.chiptripbe.module.ai.dto.AiItineraryResult;
import com.tranbac.chiptripbe.module.ai.entity.AiUsage;
import com.tranbac.chiptripbe.module.ai.repository.AiUsageRepository;
import com.tranbac.chiptripbe.module.notification.event.AiCreditsLowEvent;
import com.tranbac.chiptripbe.module.place.entity.PlaceCache;
import com.tranbac.chiptripbe.module.trip.dto.request.GenerateTripRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.TripGenerateResponse;
import com.tranbac.chiptripbe.module.trip.entity.Activity;
import com.tranbac.chiptripbe.module.trip.entity.ChecklistItem;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.trip.entity.TripDay;
import com.tranbac.chiptripbe.module.trip.repository.ActivityRepository;
import com.tranbac.chiptripbe.module.trip.repository.ChecklistItemRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripDayRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.trip.service.TripGenerationPersistenceService;
import com.tranbac.chiptripbe.module.trip.service.TripMemberService;
import com.tranbac.chiptripbe.module.user.entity.User;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
class TripGenerationPersistenceServiceImpl implements TripGenerationPersistenceService {

    private final TripRepository tripRepository;
    private final TripDayRepository tripDayRepository;
    private final ActivityRepository activityRepository;
    private final ChecklistItemRepository checklistItemRepository;
    private final UserRepository userRepository;
    private final AiUsageRepository aiUsageRepository;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final TripMemberService tripMemberService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public TripGenerateResponse persistGeneratedTrip(Long userId,
                                                     GenerateTripRequest request,
                                                     AiCallResult aiResult,
                                                     Map<AiItineraryResult.AiActivity, PlaceCache> resolvedPlaces) {
        // Re-load user trong tx persist + tái-kiểm credits (tránh race với generate request khác)
        User user = userRepository.findById(userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy người dùng"));
        if (user.getAiCredits() <= 0) {
            throw AppException.badRequest("Hết lượt AI. Vui lòng mua thêm credits.");
        }

        AiItineraryResult itinerary = aiResult.itinerary();

        Trip trip = buildAndSaveTrip(user, request, itinerary);
        tripMemberService.seedOwner(trip, user);

        List<TripGenerateResponse.DayDetail> dayDetails = new ArrayList<>();
        long totalCost = 0;
        for (AiItineraryResult.AiDay aiDay : itinerary.getDays()) {
            DayResult dr = persistDay(trip, aiDay, request, resolvedPlaces);
            dayDetails.add(dr.detail);
            totalCost += dr.dayCost;
        }

        List<TripGenerateResponse.ChecklistDetail> checklistDetails = persistChecklist(trip, itinerary);

        // Trừ credits sau khi persist thành công
        int remainingCredits = user.getAiCredits() - 1;
        user.setAiCredits(remainingCredits);
        userRepository.save(user);

        if (remainingCredits <= 1) {
            eventPublisher.publishEvent(new AiCreditsLowEvent(userId, remainingCredits));
        }

        logAiUsage(user, trip, aiResult);

        log.info("Generated trip id={} for userId={} with {} days via AI (tokens: {}+{})",
                trip.getId(), userId, dayDetails.size(),
                aiResult.promptTokens(), aiResult.completionTokens());

        return TripGenerateResponse.builder()
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
                .totalCostVnd(totalCost)
                .days(dayDetails)
                .checklist(checklistDetails)
                .build();
    }

    private Trip buildAndSaveTrip(User user, GenerateTripRequest request, AiItineraryResult itinerary) {
        List<String> styleList = request.getStyles() != null ? request.getStyles() : List.of();
        String stylesJson;
        try {
            stylesJson = objectMapper.writeValueAsString(styleList);
        } catch (JsonProcessingException e) {
            stylesJson = "[]";
        }

        String title = (itinerary.getTitle() != null && !itinerary.getTitle().isBlank())
                ? itinerary.getTitle()
                : request.getDestination() + " "
                + (ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1)
                + " ngày";

        Trip trip = Trip.builder()
                .user(user)
                .title(title)
                .departure(request.getDeparture())
                .destination(request.getDestination())
                .dateStart(request.getStartDate())
                .dateEnd(request.getEndDate())
                .peopleCount(request.getPeopleCount())
                .budgetVnd(request.getBudgetVnd())
                .styles(stylesJson)
                .build();
        return tripRepository.save(trip);
    }

    private record DayResult(TripGenerateResponse.DayDetail detail, long dayCost) {}

    private DayResult persistDay(Trip trip,
                                 AiItineraryResult.AiDay aiDay,
                                 GenerateTripRequest request,
                                 Map<AiItineraryResult.AiActivity, PlaceCache> resolvedPlaces) {
        LocalDate dayDate;
        try {
            dayDate = LocalDate.parse(aiDay.getDate());
        } catch (Exception e) {
            dayDate = request.getStartDate().plusDays(aiDay.getDayNumber() - 1);
        }

        TripDay day = TripDay.builder()
                .trip(trip)
                .dayNumber(aiDay.getDayNumber())
                .date(dayDate)
                .build();
        day = tripDayRepository.save(day);

        List<TripGenerateResponse.ActivityDetail> activityDetails = new ArrayList<>();
        long dayCost = 0;
        int order = 1;

        for (AiItineraryResult.AiActivity aiActivity : aiDay.getActivities()) {
            LocalTime startTime;
            try {
                startTime = LocalTime.parse(aiActivity.getTime());
            } catch (Exception e) {
                startTime = LocalTime.of(8, 0);
            }

            long cost = aiActivity.getCostVnd() != null ? Math.max(0, aiActivity.getCostVnd()) : 0L;
            dayCost += cost;

            ActivityType activityType = parseActivityType(aiActivity.getType());

            PlaceCache p = resolvedPlaces != null ? resolvedPlaces.get(aiActivity) : null;
            BigDecimal latitude = p != null ? p.getLatitude() : null;
            BigDecimal longitude = p != null ? p.getLongitude() : null;
            String placeId = p != null ? p.getGoongPlaceId() : null;
            String formattedAddress = p != null ? p.getAddress() : null;
            String geocodingProvider = p != null ? "goong" : null;
            Long placeCacheId = p != null ? p.getId() : null;
            String activityImageUrl = p != null ? extractFirstPhotoUrl(p.getPhotosJson()) : null;

            Activity activity = Activity.builder()
                    .day(day)
                    .displayOrder(order++)
                    .startTime(startTime)
                    .name(aiActivity.getName())
                    .description(aiActivity.getDescription())
                    .type(activityType)
                    .costVnd(cost)
                    .latitude(latitude)
                    .longitude(longitude)
                    .searchQuery(aiActivity.getSearchQuery())
                    .placeId(placeId)
                    .formattedAddress(formattedAddress)
                    .geocodingProvider(geocodingProvider)
                    .placeCacheId(placeCacheId)
                    .imageUrl(activityImageUrl)
                    .bookingUrl(aiActivity.getBookingUrl())
                    .build();
            activity = activityRepository.save(activity);
            activityDetails.add(toActivityDetail(activity));
        }

        TripGenerateResponse.DayDetail detail = TripGenerateResponse.DayDetail.builder()
                .id(day.getId())
                .dayNumber(day.getDayNumber())
                .date(day.getDate())
                .dayCostVnd(dayCost)
                .activities(activityDetails)
                .build();
        return new DayResult(detail, dayCost);
    }

    private List<TripGenerateResponse.ChecklistDetail> persistChecklist(Trip trip, AiItineraryResult itinerary) {
        List<TripGenerateResponse.ChecklistDetail> checklistDetails = new ArrayList<>();
        if (itinerary.getChecklist() == null) return checklistDetails;

        int order = 0;
        for (AiItineraryResult.AiChecklistItem aiItem : itinerary.getChecklist()) {
            ChecklistItem item = ChecklistItem.builder()
                    .trip(trip)
                    .category(parseChecklistCategory(aiItem.getCategory()))
                    .name(aiItem.getName())
                    .isChecked(false)
                    .displayOrder(order)
                    .build();
            checklistItemRepository.save(item);
            checklistDetails.add(TripGenerateResponse.ChecklistDetail.builder()
                    .id(item.getId())
                    .category(item.getCategory().name())
                    .name(item.getName())
                    .isChecked(false)
                    .displayOrder(order)
                    .build());
            order++;
        }
        return checklistDetails;
    }

    private void logAiUsage(User user, Trip trip, AiCallResult aiResult) {
        BigDecimal costUsd = BigDecimal.valueOf(
                (double) aiResult.promptTokens() / 1_000_000 * aiProperties.getPricing().getInputUsdPer1m()
                        + (double) aiResult.completionTokens() / 1_000_000 * aiProperties.getPricing().getOutputUsdPer1m()
        ).setScale(6, RoundingMode.HALF_UP);

        aiUsageRepository.save(AiUsage.builder()
                .user(user)
                .trip(trip)
                .provider("gemini")
                .tokensIn(aiResult.promptTokens())
                .tokensOut(aiResult.completionTokens())
                .costUsd(costUsd)
                .build());
    }

    private ActivityType parseActivityType(String type) {
        if (type == null) return ActivityType.OTHER;
        try {
            return ActivityType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ActivityType.OTHER;
        }
    }

    private ChecklistCategory parseChecklistCategory(String category) {
        if (category == null) return ChecklistCategory.OTHER;
        try {
            return ChecklistCategory.valueOf(category.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ChecklistCategory.OTHER;
        }
    }

    private TripGenerateResponse.ActivityDetail toActivityDetail(Activity activity) {
        return TripGenerateResponse.ActivityDetail.builder()
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
                .placeCacheId(activity.getPlaceCacheId())
                .address(activity.getFormattedAddress())
                .build();
    }

    private String extractFirstPhotoUrl(String photosJson) {
        if (photosJson == null || photosJson.isBlank()) return null;
        try {
            List<Map<String, String>> raw = objectMapper.readValue(photosJson, new TypeReference<>() {});
            if (raw != null && !raw.isEmpty()) {
                Map<String, String> first = raw.get(0);
                String url = first.get("thumbnail");
                if (url == null || url.isBlank()) {
                    url = first.get("url");
                }
                return url;
            }
        } catch (Exception ignored) {}
        return null;
    }
}
