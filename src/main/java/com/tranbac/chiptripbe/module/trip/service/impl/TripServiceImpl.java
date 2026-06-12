package com.tranbac.chiptripbe.module.trip.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.common.enums.ActivityType;
import com.tranbac.chiptripbe.common.enums.TripLifecycleStatus;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.ai.dto.AiCallResult;
import com.tranbac.chiptripbe.module.ai.dto.AiItineraryResult;
import com.tranbac.chiptripbe.module.ai.repository.AiUsageRepository;
import com.tranbac.chiptripbe.module.ai.service.AiService;
import com.tranbac.chiptripbe.module.place.entity.PlaceCache;
import com.tranbac.chiptripbe.module.place.repository.PlaceCacheRepository;
import com.tranbac.chiptripbe.module.place.service.PlaceEnrichmentService;
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
import com.tranbac.chiptripbe.module.trip.repository.TripCommentRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripDayRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripLikeRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripMemberRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.trip.service.TripGenerationPersistenceService;
import com.tranbac.chiptripbe.module.trip.service.TripMemberService;
import com.tranbac.chiptripbe.module.trip.service.TripService;
import com.tranbac.chiptripbe.module.user.entity.User;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

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
    private final TripLikeRepository tripLikeRepository;
    private final TripCommentRepository tripCommentRepository;
    private final TripMemberService tripMemberService;
    private final UserRepository userRepository;
    private final AiService aiService;
    private final AiUsageRepository aiUsageRepository;
    private final ObjectMapper objectMapper;
    private final PlaceEnrichmentService placeEnrichmentService;
    private final PlaceCacheRepository placeCacheRepository;
    private final TripGenerationPersistenceService tripGenerationPersistenceService;
    @Qualifier("enrichmentExecutor")
    private final Executor enrichmentExecutor;

    /**
     * Orchestration only — KHÔNG annotate @Transactional.
     * Tránh giữ DB transaction trong khi gọi Gemini và resolve place (HTTP calls dài).
     *
     * Flow:
     * 1. Validate request + read-only check user/credits (mỗi findById tự mở tx ngắn).
     * 2. Gọi AI ngoài transaction.
     * 3. Validate AI output (fail-fast nếu invalid — không tốn lượt AI, không persist trip rác).
     * 4. Resolve place (mỗi resolvePlace tự mở tx ngắn để upsert PlaceCache).
     * 5. Persist trip + days + activities + checklist + AiUsage trong 1 tx ngắn.
     *
     * propagation = NOT_SUPPORTED để override class-level @Transactional(readOnly=true) —
     * tránh giữ DB connection trong khi gọi Gemini.
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TripGenerateResponse generateTrip(Long userId, GenerateTripRequest request) {
        String userPreferences = validateRequestAndCredits(userId, request);

        AiCallResult aiCallResult = aiService.generateItinerary(request, userPreferences);

        Map<AiItineraryResult.AiActivity, PlaceCache> resolvedPlaces =
                resolvePlaces(aiCallResult.itinerary(), request);

        return tripGenerationPersistenceService.persistGeneratedTrip(
                userId, request, aiCallResult, resolvedPlaces);
    }

    /** @return User.preferences (gu du lịch đã lưu) để cá nhân hóa prompt AI — null nếu chưa có. */
    private String validateRequestAndCredits(Long userId, GenerateTripRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy người dùng"));

        if (user.getAiCredits() <= 0) {
            throw AppException.badRequest("Hết lượt AI. Vui lòng mua thêm credits.");
        }

        LocalDate today = LocalDate.now();
        if (request.getStartDate().isBefore(today)) {
            throw AppException.badRequest("Ngày bắt đầu không được trước ngày hôm nay");
        }
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw AppException.badRequest("Ngày kết thúc không được trước ngày bắt đầu");
        }
        return user.getPreferences();
    }

    /**
     * Thu thập tất cả geocodable activities từ itinerary rồi resolve song song.
     * Fail-soft: 1 activity resolve fail không làm fail toàn bộ generate trip.
     */
    private Map<AiItineraryResult.AiActivity, PlaceCache> resolvePlaces(
            AiItineraryResult itinerary, GenerateTripRequest request) {
        if (itinerary.getDays() == null) return new ConcurrentHashMap<>();

        List<AiItineraryResult.AiActivity> geocodable = new ArrayList<>();
        for (AiItineraryResult.AiDay day : itinerary.getDays()) {
            if (day.getActivities() == null) continue;
            for (AiItineraryResult.AiActivity act : day.getActivities()) {
                ActivityType type = parseActivityType(act.getType());
                if (!shouldGeocode(type)) continue;
                if (act.getSearchQuery() == null || act.getSearchQuery().isBlank()) continue;
                geocodable.add(act);
            }
        }

        return resolveAllPlacesParallel(
                geocodable,
                request.getDestination(),
                request.getStartDate(),
                request.getEndDate(),
                request.getPeopleCount());
    }

    private Map<AiItineraryResult.AiActivity, PlaceCache> resolveAllPlacesParallel(
            List<AiItineraryResult.AiActivity> activities,
            String destination,
            LocalDate checkIn,
            LocalDate checkOut,
            Integer adults) {
        Map<AiItineraryResult.AiActivity, PlaceCache> result = new ConcurrentHashMap<>();

        List<CompletableFuture<Void>> futures = activities.stream()
                .map(act -> CompletableFuture.runAsync(() -> {
                    try {
                        Optional<PlaceCache> placeOpt = placeEnrichmentService.resolvePlace(act.getSearchQuery(), destination);
                        placeOpt.ifPresent(cache -> {
                            result.put(act, cache);
                            if (parseActivityType(act.getType()) == ActivityType.ACCOMMODATION) {
                                placeEnrichmentService.enrichAccommodation(cache, checkIn, checkOut, adults);
                            }
                        });
                    } catch (Exception e) {
                        log.warn("Resolve place failed (skip): activity='{}', searchQuery='{}', error={}",
                                act.getName(), act.getSearchQuery(), e.getMessage());
                    }
                }, enrichmentExecutor))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(60, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("Parallel enrichment timeout/error: {}", ex.getMessage());
                    return null;
                })
                .join();

        return result;
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
        // likes/comments dùng cột tripId Long thuần (không FK cascade) — phải cleanup tay
        tripLikeRepository.deleteByTripId(tripId);
        tripCommentRepository.deleteByTripId(tripId);
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

    @Override
    @Transactional(readOnly = true)
    public TripDetailResponse getPublicTrip(Long tripId) {
        Trip trip = tripRepository.findById(tripId)
                .filter(Trip::isPublic)
                .orElseThrow(() -> AppException.notFound("Không tìm thấy chuyến đi"));
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
                .isPublic(trip.isPublic())
                .publishedAt(trip.getPublishedAt())
                .likesCount(trip.getLikesCount())
                .commentsCount(trip.getCommentsCount())
                .status(TripLifecycleStatus.of(trip.getDateStart(), trip.getDateEnd(), LocalDate.now()).name())
                .user(TripDetailResponse.UserInfo.builder()
                        .id(trip.getUser().getId())
                        .email(trip.getUser().getEmail())
                        .fullName(trip.getUser().getFullName())
                        .avatarUrl(trip.getUser().getAvatarUrl())
                        .build())
                .members(members)
                .days(dayDetails)
                .checklist(checklistDetails)
                .build();
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
                .isPublic(trip.isPublic())
                .likesCount(trip.getLikesCount())
                .commentsCount(trip.getCommentsCount())
                .status(TripLifecycleStatus.of(trip.getDateStart(), trip.getDateEnd(), LocalDate.now()).name())
                .build();
    }

    /** FOOD, ATTRACTION, ACCOMMODATION, TRANSPORT đều cần geocode khi có searchQuery. OTHER bỏ qua. */
    private boolean shouldGeocode(ActivityType type) {
        return type == ActivityType.FOOD
                || type == ActivityType.ATTRACTION
                || type == ActivityType.ACCOMMODATION
                || type == ActivityType.TRANSPORT;
    }

    private ActivityType parseActivityType(String type) {
        if (type == null) return ActivityType.OTHER;
        try {
            return ActivityType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ActivityType.OTHER;
        }
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
