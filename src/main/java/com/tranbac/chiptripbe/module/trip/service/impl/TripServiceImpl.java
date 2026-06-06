package com.tranbac.chiptripbe.module.trip.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.common.config.AiProperties;
import com.tranbac.chiptripbe.common.enums.ActivityType;
import com.tranbac.chiptripbe.common.enums.ChecklistCategory;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.ai.dto.AiCallResult;
import com.tranbac.chiptripbe.module.ai.dto.AiItineraryResult;
import com.tranbac.chiptripbe.module.ai.entity.AiUsage;
import com.tranbac.chiptripbe.module.ai.repository.AiUsageRepository;
import com.tranbac.chiptripbe.module.ai.service.AiService;
import com.tranbac.chiptripbe.module.notification.event.AiCreditsLowEvent;
import com.tranbac.chiptripbe.module.place.entity.PlaceCache;
import com.tranbac.chiptripbe.module.place.service.PlaceEnrichmentService;
import com.tranbac.chiptripbe.module.place.repository.PlaceCacheRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.tranbac.chiptripbe.module.trip.dto.request.GenerateTripRequest;
import com.tranbac.chiptripbe.module.trip.dto.request.UpdateTripRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.ShareTokenResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripDetailResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripGenerateResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripMemberResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.TripSummaryResponse;
import com.tranbac.chiptripbe.module.trip.entity.Activity;
import com.tranbac.chiptripbe.module.trip.entity.ChecklistItem;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.trip.entity.TripDay;
import com.tranbac.chiptripbe.module.trip.repository.ActivityRepository;
import com.tranbac.chiptripbe.module.trip.repository.ChecklistItemRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripDayRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripMemberRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.trip.service.TripMemberService;
import com.tranbac.chiptripbe.module.trip.service.TripService;
import com.tranbac.chiptripbe.module.user.entity.User;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class TripServiceImpl implements TripService {

    private final TripRepository tripRepository;
    private final TripDayRepository tripDayRepository;
    private final ActivityRepository activityRepository;
    private final ChecklistItemRepository checklistItemRepository;
    private final TripMemberRepository tripMemberRepository;
    private final TripMemberService tripMemberService;
    private final UserRepository userRepository;
    private final AiService aiService;
    private final AiUsageRepository aiUsageRepository;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final PlaceEnrichmentService placeEnrichmentService;
    private final PlaceCacheRepository placeCacheRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public TripGenerateResponse generateTrip(Long userId, GenerateTripRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy người dùng"));

        if (user.getAiCredits() <= 0) {
            throw AppException.badRequest("Hết lượt AI. Vui lòng mua thêm credits.");
        }

        LocalDate today = LocalDate.now();
        if (request.getStartDate().isBefore(today)) {
            throw AppException.badRequest("Ngày bắt đầu không được trước ngày hôm nay");
        }
        if (!request.getEndDate().isAfter(request.getStartDate())) {
            throw AppException.badRequest("Ngày kết thúc phải sau ngày bắt đầu");
        }

        // Gọi AI — thực hiện trước khi ghi DB để không giữ transaction trong khi chờ HTTP
        AiCallResult aiCallResult = aiService.generateItinerary(request);
        AiItineraryResult itinerary = aiCallResult.itinerary();

        // Styles JSON
        List<String> styleList = request.getStyles() != null ? request.getStyles() : List.of();
        String stylesJson;
        try {
            stylesJson = objectMapper.writeValueAsString(styleList);
        } catch (JsonProcessingException e) {
            stylesJson = "[]";
        }

        // Title: ưu tiên title từ AI, fallback destination + số ngày
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
        trip = tripRepository.save(trip);
        tripMemberService.seedOwner(trip, user);

        // Map AI days → entities
        long totalCost = 0;
        List<TripGenerateResponse.DayDetail> dayDetails = new ArrayList<>();

        for (AiItineraryResult.AiDay aiDay : itinerary.getDays()) {
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

                BigDecimal latitude = null;
                BigDecimal longitude = null;
                String placeId = null;
                String formattedAddress = null;
                String geocodingProvider = null;
                Long placeCacheId = null;

                String activityImageUrl = null;
                if (shouldGeocode(activityType) && aiActivity.getSearchQuery() != null && !aiActivity.getSearchQuery().isBlank()) {
                    Optional<PlaceCache> place = placeEnrichmentService.resolvePlace(
                            aiActivity.getSearchQuery(), request.getDestination());
                    if (place.isPresent()) {
                        PlaceCache p = place.get();
                        latitude = p.getLatitude();
                        longitude = p.getLongitude();
                        placeId = p.getGoongPlaceId();
                        formattedAddress = p.getAddress();
                        geocodingProvider = "goong";
                        placeCacheId = p.getId();
                        activityImageUrl = extractFirstPhotoUrl(p.getPhotosJson());
                    }
                }

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

            totalCost += dayCost;
            dayDetails.add(TripGenerateResponse.DayDetail.builder()
                    .id(day.getId())
                    .dayNumber(day.getDayNumber())
                    .date(day.getDate())
                    .dayCostVnd(dayCost)
                    .activities(activityDetails)
                    .build());
        }

        // Map AI checklist → entities
        List<TripGenerateResponse.ChecklistDetail> checklistDetails = new ArrayList<>();
        int checklistOrder = 0;
        for (AiItineraryResult.AiChecklistItem aiItem : itinerary.getChecklist()) {
            ChecklistItem item = ChecklistItem.builder()
                    .trip(trip)
                    .category(parseChecklistCategory(aiItem.getCategory()))
                    .name(aiItem.getName())
                    .isChecked(false)
                    .displayOrder(checklistOrder)
                    .build();
            checklistItemRepository.save(item);
            checklistDetails.add(TripGenerateResponse.ChecklistDetail.builder()
                    .id(item.getId())
                    .category(item.getCategory().name())
                    .name(item.getName())
                    .isChecked(false)
                    .displayOrder(checklistOrder)
                    .build());
            checklistOrder++;
        }

        // Trừ AI credits sau khi persist thành công
        int remainingCredits = user.getAiCredits() - 1;
        user.setAiCredits(remainingCredits);
        userRepository.save(user);

        // Cảnh báo khi credits thấp (sau khi trừ còn 0 hoặc 1) — publish event để
        // listener AFTER_COMMIT chỉ tạo noti khi toàn bộ transaction này thành công.
        if (remainingCredits <= 1) {
            eventPublisher.publishEvent(new AiCreditsLowEvent(userId, remainingCredits));
        }

        // Log AI usage
        BigDecimal costUsd = BigDecimal.valueOf(
                (double) aiCallResult.promptTokens() / 1_000_000 * aiProperties.getPricing().getInputUsdPer1m()
                + (double) aiCallResult.completionTokens() / 1_000_000 * aiProperties.getPricing().getOutputUsdPer1m()
        ).setScale(6, RoundingMode.HALF_UP);

        aiUsageRepository.save(AiUsage.builder()
                .user(user)
                .trip(trip)
                .provider("gemini")
                .tokensIn(aiCallResult.promptTokens())
                .tokensOut(aiCallResult.completionTokens())
                .costUsd(costUsd)
                .build());

        log.info("Generated trip id={} for userId={} with {} days via AI (tokens: {}+{})",
                trip.getId(), userId, dayDetails.size(),
                aiCallResult.promptTokens(), aiCallResult.completionTokens());

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

    @Override
    @Transactional(readOnly = true)
    public Page<TripSummaryResponse> getMyTrips(Long userId, Pageable pageable) {
        Page<Trip> page = tripRepository.findByUserId(userId, pageable);
        List<Long> tripIds = page.getContent().stream().map(Trip::getId).collect(java.util.stream.Collectors.toList());
        Map<Long, String> imageUrlMap = new HashMap<>();
        if (!tripIds.isEmpty()) {
            List<Object[]> rows = activityRepository.findFirstImageUrlsForTrips(tripIds);
            for (Object[] row : rows) {
                Long tripId = row[0] instanceof Number ? ((Number) row[0]).longValue() : Long.parseLong(row[0].toString());
                String imgUrl = row[1] != null ? row[1].toString() : null;
                imageUrlMap.putIfAbsent(tripId, imgUrl);
            }
        }
        return page.map(trip -> toSummaryResponse(trip, imageUrlMap.get(trip.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public TripDetailResponse getTripDetail(Long userId, Long tripId) {
        Trip trip = findTripByIdAndUserId(tripId, userId);
        return buildTripDetailResponse(trip);
    }

    @Override
    @Transactional
    public TripDetailResponse updateTrip(Long userId, Long tripId, UpdateTripRequest request) {
        Trip trip = findTripByIdAndUserId(tripId, userId);

        if (request.getTitle() != null) {
            trip.setTitle(request.getTitle());
        }
        if (request.getDateStart() != null) {
            trip.setDateStart(request.getDateStart());
        }
        if (request.getDateEnd() != null) {
            trip.setDateEnd(request.getDateEnd());
        }
        if (request.getPeopleCount() != null) {
            trip.setPeopleCount(request.getPeopleCount());
        }
        if (request.getBudgetVnd() != null) {
            trip.setBudgetVnd(request.getBudgetVnd());
        }
        if (request.getStyles() != null) {
            try {
                trip.setStyles(objectMapper.writeValueAsString(request.getStyles()));
            } catch (JsonProcessingException e) {
                // ignore
            }
        }

        trip = tripRepository.save(trip);
        log.info("Updated trip id={} by userId={}", tripId, userId);
        return buildTripDetailResponse(trip);
    }

    @Override
    @Transactional
    public void deleteTrip(Long userId, Long tripId) {
        Trip trip = findTripByIdAndUserId(tripId, userId);
        aiUsageRepository.nullifyTripReference(tripId);
        tripRepository.delete(trip);
        log.info("Deleted trip id={} by userId={}", tripId, userId);
    }

    @Override
    @Transactional
    public TripDetailResponse cloneTrip(Long userId, Long tripId) {
        Trip original = findTripByIdAndUserId(tripId, userId);

        Trip clone = Trip.builder()
                .user(original.getUser())
                .title(original.getTitle() + " (Copy)")
                .departure(original.getDeparture())
                .destination(original.getDestination())
                .dateStart(original.getDateStart())
                .dateEnd(original.getDateEnd())
                .peopleCount(original.getPeopleCount())
                .budgetVnd(original.getBudgetVnd())
                .styles(original.getStyles())
                .build();
        clone = tripRepository.save(clone);
        tripMemberService.seedOwner(clone, original.getUser());

        // Clone days and activities
        for (TripDay originalDay : original.getDays()) {
            TripDay cloneDay = TripDay.builder()
                    .trip(clone)
                    .dayNumber(originalDay.getDayNumber())
                    .date(originalDay.getDate())
                    .build();
            cloneDay = tripDayRepository.save(cloneDay);

            for (Activity originalActivity : originalDay.getActivities()) {
                Activity cloneActivity = Activity.builder()
                        .day(cloneDay)
                        .startTime(originalActivity.getStartTime())
                        .name(originalActivity.getName())
                        .description(originalActivity.getDescription())
                        .type(originalActivity.getType())
                        .costVnd(originalActivity.getCostVnd())
                        .latitude(originalActivity.getLatitude())
                        .longitude(originalActivity.getLongitude())
                        .imageUrl(originalActivity.getImageUrl())
                        .bookingUrl(originalActivity.getBookingUrl())
                        .displayOrder(originalActivity.getDisplayOrder())
                        .build();
                activityRepository.save(cloneActivity);
            }
        }

        // Clone checklist
        for (ChecklistItem originalItem : original.getChecklist()) {
            ChecklistItem cloneItem = ChecklistItem.builder()
                    .trip(clone)
                    .category(originalItem.getCategory())
                    .name(originalItem.getName())
                    .isChecked(false)
                    .displayOrder(originalItem.getDisplayOrder())
                    .build();
            checklistItemRepository.save(cloneItem);
        }

        log.info("Cloned trip id={} to new trip id={} by userId={}", tripId, clone.getId(), userId);
        return buildTripDetailResponse(clone);
    }

    @Override
    @Transactional
    public ShareTokenResponse enableShare(Long userId, Long tripId) {
        Trip trip = findTripByIdAndUserId(tripId, userId);
        String token = UUID.randomUUID().toString().replace("-", "");
        trip.setShareToken(token);
        tripRepository.save(trip);
        log.info("Enabled share for trip id={}, token={}", tripId, token);
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
    public TripDetailResponse getSharedTrip(String shareToken) {
        Trip trip = tripRepository.findByShareToken(shareToken)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi được chia sẻ"));
        return buildTripDetailResponse(trip);
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private Trip findTripByIdAndUserId(Long tripId, Long userId) {
        Trip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi"));
        if (!trip.getUser().getId().equals(userId)) {
            throw AppException.forbidden("Bạn không có quyền với chuyến đi này");
        }
        return trip;
    }

    private TripDetailResponse buildTripDetailResponse(Trip trip) {
        List<TripDay> days = tripDayRepository.findByTripIdOrderByDayNumber(trip.getId());
        List<ChecklistItem> checklist = checklistItemRepository.findByTripIdOrderByDisplayOrder(trip.getId());

        List<Long> placeCacheIds = days.stream()
                .flatMap(day -> activityRepository.findByDayIdOrderByDisplayOrder(day.getId()).stream())
                .map(Activity::getPlaceCacheId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, PlaceCache> cacheMap = new HashMap<>();
        if (!placeCacheIds.isEmpty()) {
            placeCacheRepository.findAllById(placeCacheIds).forEach(p -> cacheMap.put(p.getId(), p));
        }

        List<TripDetailResponse.DayDetail> dayDetails = days.stream().map(day -> {
            List<Activity> activities = activityRepository.findByDayIdOrderByDisplayOrder(day.getId());
            List<TripDetailResponse.ActivityDetail> activityDetails = activities.stream()
                    .map(act -> toTripDetailActivityDetail(act, cacheMap)).toList();
            return TripDetailResponse.DayDetail.builder()
                    .id(day.getId())
                    .dayNumber(day.getDayNumber())
                    .date(day.getDate())
                    .dayCostVnd(activityDetails.stream().mapToLong(a -> a.getCostVnd() != null ? a.getCostVnd() : 0L).sum())
                    .activities(activityDetails)
                    .build();
        }).toList();

        List<TripDetailResponse.ChecklistItemDetail> checklistDetails = checklist.stream()
                .map(c -> TripDetailResponse.ChecklistItemDetail.builder()
                        .id(c.getId())
                        .category(c.getCategory() != null ? c.getCategory().name() : null)
                        .name(c.getName())
                        .isChecked(c.getIsChecked())
                        .displayOrder(c.getDisplayOrder())
                        .build()).toList();

        List<TripMemberResponse> members = tripMemberRepository.findByTripIdWithUser(trip.getId())
                .stream().map(m -> TripMemberResponse.builder()
                        .id(m.getId())
                        .userId(m.getUser() != null ? m.getUser().getId() : null)
                        .displayName(m.getDisplayName())
                        .avatarUrl(m.getUser() != null ? m.getUser().getAvatarUrl() : null)
                        .role(m.getRole())
                        .createdAt(m.getCreatedAt())
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
                .totalCostVnd(dayDetails.stream().mapToLong(d -> d.getDayCostVnd() != null ? d.getDayCostVnd() : 0L).sum())
                .shareToken(trip.getShareToken())
                .user(TripDetailResponse.UserInfo.builder()
                        .id(trip.getUser().getId())
                        .email(trip.getUser().getEmail())
                        .fullName(trip.getUser().getFullName())
                        .build())
                .members(members)
                .days(dayDetails)
                .checklist(checklistDetails)
                .build();
    }

    private TripSummaryResponse toSummaryResponse(Trip trip) {
        return toSummaryResponse(trip, null);
    }

    private TripSummaryResponse toSummaryResponse(Trip trip, String imageUrl) {
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
                .updatedAt(trip.getUpdatedAt())
                .imageUrl(imageUrl)
                .build();
    }

    private boolean shouldGeocode(ActivityType type) {
        return type == ActivityType.FOOD || type == ActivityType.ATTRACTION || type == ActivityType.ACCOMMODATION;
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

    private TripDetailResponse.ActivityDetail toTripDetailActivityDetail(Activity activity, Map<Long, PlaceCache> cacheMap) {
        String imgUrl = activity.getImageUrl();
        if ((imgUrl == null || imgUrl.isBlank()) && activity.getPlaceCacheId() != null) {
            PlaceCache p = cacheMap.get(activity.getPlaceCacheId());
            if (p != null) {
                imgUrl = extractFirstPhotoUrl(p.getPhotosJson());
            }
        }

        return TripDetailResponse.ActivityDetail.builder()
                .id(activity.getId())
                .startTime(activity.getStartTime())
                .name(activity.getName())
                .description(activity.getDescription())
                .type(activity.getType())
                .costVnd(activity.getCostVnd())
                .latitude(activity.getLatitude())
                .longitude(activity.getLongitude())
                .imageUrl(imgUrl)
                .bookingUrl(activity.getBookingUrl())
                .displayOrder(activity.getDisplayOrder())
                .placeCacheId(activity.getPlaceCacheId())
                .address(activity.getFormattedAddress())
                .build();
    }

    private String extractFirstPhotoUrl(String photosJson) {
        if (photosJson == null || photosJson.isBlank()) return null;
        try {
            List<Map<String, String>> raw = objectMapper.readValue(photosJson, new TypeReference<List<Map<String, String>>>() {});
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
