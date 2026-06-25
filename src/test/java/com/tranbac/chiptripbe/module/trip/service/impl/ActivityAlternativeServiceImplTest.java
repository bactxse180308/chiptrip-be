package com.tranbac.chiptripbe.module.trip.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.common.enums.ActivityType;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.ai.service.AiUsageService;
import com.tranbac.chiptripbe.module.trip.dto.request.ActivityAlternativesRequest;
import com.tranbac.chiptripbe.module.place.entity.PlaceCache;
import com.tranbac.chiptripbe.module.place.repository.PlaceCacheRepository;
import com.tranbac.chiptripbe.module.place.service.PlaceEnrichmentService;
import com.tranbac.chiptripbe.module.trip.dto.request.ReplaceActivityRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.ActivityAlternativeOptionResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.ActivityAlternativesResponse;
import com.tranbac.chiptripbe.module.trip.dto.response.ReplaceActivityResponse;
import com.tranbac.chiptripbe.module.trip.entity.Activity;
import com.tranbac.chiptripbe.module.trip.entity.ActivityAlternativeSession;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.trip.entity.TripDay;
import com.tranbac.chiptripbe.module.trip.enums.ActivityAlternativeCategory;
import com.tranbac.chiptripbe.module.trip.enums.ActivityAlternativeSessionStatus;
import com.tranbac.chiptripbe.module.trip.repository.ActivityAlternativeSessionRepository;
import com.tranbac.chiptripbe.module.trip.repository.ActivityRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripDayRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import com.tranbac.chiptripbe.module.user.service.EntitlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActivityAlternativeServiceImplTest {

    private static final long USER_ID = 7L;
    private static final long TRIP_ID = 11L;
    private static final long DAY_ID = 21L;
    private static final long ACTIVITY_ID = 31L;

    @Mock private TripRepository tripRepository;
    @Mock private TripDayRepository tripDayRepository;
    @Mock private ActivityRepository activityRepository;
    @Mock private ActivityAlternativeSessionRepository sessionRepository;
    @Mock private PlaceCacheRepository placeCacheRepository;
    @Mock private UserRepository userRepository;
    @Mock private EntitlementService entitlementService;
    @Mock private PlaceEnrichmentService placeEnrichmentService;
    @Mock private AiUsageService aiUsageService;

    private ActivityAlternativeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ActivityAlternativeServiceImpl(
                tripRepository,
                tripDayRepository,
                activityRepository,
                sessionRepository,
                placeCacheRepository,
                userRepository,
                entitlementService,
                placeEnrichmentService,
                Runnable::run,
                WebClient.builder(),
                new com.tranbac.chiptripbe.common.config.AiProperties(),
                new ObjectMapper(),
                aiUsageService);
    }

    @Test
    void replaceActivity_freeQuotaAvailable_doesNotDeductCreditUnits() throws Exception {
        Trip trip = trip(4, 3);
        Activity activity = activity(trip);
        stubReplaceGraph(trip, activity, sessionJson());
        when(userRepository.findAiCreditUnitsById(USER_ID)).thenReturn(300);

        ReplaceActivityResponse response = service.replaceActivity(USER_ID, TRIP_ID, DAY_ID, ACTIVITY_ID, replaceRequest());

        assertEquals("Quan an moi", activity.getName());
        assertEquals(4, trip.getActivitySwapFreeUsed());
        assertEquals(0, response.getChargedUnits());
        assertEquals(0, response.getFreeSwapsRemaining());
        verify(userRepository, never()).deductCreditUnitsIfAvailable(any(), anyInt());
    }

    @Test
    void replaceActivity_freeQuotaExhausted_deductsQuarterCredit() throws Exception {
        Trip trip = trip(4, 4);
        Activity activity = activity(trip);
        stubReplaceGraph(trip, activity, sessionJson());
        when(userRepository.deductCreditUnitsIfAvailable(USER_ID, 25)).thenReturn(1);
        when(userRepository.findAiCreditUnitsById(USER_ID)).thenReturn(275);

        ReplaceActivityResponse response = service.replaceActivity(USER_ID, TRIP_ID, DAY_ID, ACTIVITY_ID, replaceRequest());

        assertEquals(25, response.getChargedUnits());
        assertEquals(4, trip.getActivitySwapFreeUsed());
        verify(userRepository).deductCreditUnitsIfAvailable(USER_ID, 25);
    }

    @Test
    void replaceActivity_placeCacheHasSerpTitle_writesResolvedPlaceIdentity() throws Exception {
        Trip trip = trip(4, 3);
        Activity activity = activity(trip);
        stubReplaceGraph(trip, activity, sessionJsonWithPlaceCache());
        when(placeCacheRepository.findById(901L)).thenReturn(Optional.of(placeCache()));
        when(userRepository.findAiCreditUnitsById(USER_ID)).thenReturn(300);

        service.replaceActivity(USER_ID, TRIP_ID, DAY_ID, ACTIVITY_ID, replaceRequest());

        assertEquals("Thai Market Restaurant - Ba Na Hills", activity.getName());
        assertEquals("Thai Market Restaurant - Ba Na Hills, Ba Na Hills, Da Nang", activity.getSearchQuery());
        assertEquals("Ba Na Hills, Da Nang", activity.getFormattedAddress());
        assertEquals("https://cdn.example.com/thumb.jpg", activity.getImageUrl());
    }

    @Test
    void replaceActivity_alreadyAppliedSession_rejectsBeforeCharging() {
        when(sessionRepository.findByIdAndUserIdAndTripIdAndDayIdAndActivityIdForUpdate(
                101L, USER_ID, TRIP_ID, DAY_ID, ACTIVITY_ID))
                .thenReturn(Optional.of(ActivityAlternativeSession.builder()
                        .userId(USER_ID)
                        .tripId(TRIP_ID)
                        .dayId(DAY_ID)
                        .activityId(ACTIVITY_ID)
                        .category(ActivityAlternativeCategory.RESTAURANT)
                        .status(ActivityAlternativeSessionStatus.APPLIED)
                        .expiresAt(LocalDateTime.now().plusMinutes(5))
                        .build()));

        assertThrows(AppException.class,
                () -> service.replaceActivity(USER_ID, TRIP_ID, DAY_ID, ACTIVITY_ID, replaceRequest()));

        verify(userRepository, never()).deductCreditUnitsIfAvailable(any(), anyInt());
        verify(activityRepository, never()).saveAll(any());
    }

    @Test
    void getActiveAlternatives_returnsPendingSessionFromDatabase() throws Exception {
        Trip trip = trip(4, 1);
        Activity activity = activity(trip);
        ActivityAlternativeSession session = pendingSession(sessionJson());
        stubCreateGraph(trip, activity, List.of(activity));
        when(sessionRepository.findFirstByUserIdAndTripIdAndDayIdAndActivityIdAndStatusAndExpiresAtAfterOrderByIdDesc(
                eq(USER_ID),
                eq(TRIP_ID),
                eq(DAY_ID),
                eq(ACTIVITY_ID),
                eq(ActivityAlternativeSessionStatus.PENDING),
                any(LocalDateTime.class)))
                .thenReturn(Optional.of(session));

        ActivityAlternativesResponse response =
                service.getActiveAlternatives(USER_ID, TRIP_ID, DAY_ID, ACTIVITY_ID);

        assertEquals(101L, response.getSessionId());
        assertEquals(ActivityAlternativeCategory.RESTAURANT, response.getCategory());
        assertEquals(3, response.getFreeSwapsRemaining());
        assertEquals("Quan an moi", response.getOptions().get(0).getName());
    }

    @Test
    void createAlternatives_reusesPendingSessionForSameCategory() throws Exception {
        Trip trip = trip(4, 1);
        Activity activity = activity(trip);
        ActivityAlternativeSession session = pendingSession(sessionJson());
        stubCreateGraph(trip, activity, List.of(activity));
        when(sessionRepository.findFirstByUserIdAndTripIdAndDayIdAndActivityIdAndCategoryAndStatusAndExpiresAtAfterOrderByIdDesc(
                eq(USER_ID),
                eq(TRIP_ID),
                eq(DAY_ID),
                eq(ACTIVITY_ID),
                eq(ActivityAlternativeCategory.RESTAURANT),
                eq(ActivityAlternativeSessionStatus.PENDING),
                any(LocalDateTime.class)))
                .thenReturn(Optional.of(session));

        ActivityAlternativesResponse response = service.createAlternatives(
                USER_ID, TRIP_ID, DAY_ID, ACTIVITY_ID, alternativesRequest(ActivityAlternativeCategory.RESTAURANT));

        assertEquals(101L, response.getSessionId());
        assertEquals("Quan an moi", response.getOptions().get(0).getName());
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void createAlternatives_currentAccommodationRejectsNonHotelCategoryBeforeAiCall() {
        Trip trip = trip(4, 0);
        Activity current = activity(trip);
        current.setType(ActivityType.ACCOMMODATION);
        current.setName("Nhan phong khach san");
        stubCreateGraph(trip, current, List.of(current));

        assertThrows(AppException.class, () -> service.createAlternatives(
                USER_ID, TRIP_ID, DAY_ID, ACTIVITY_ID, alternativesRequest(ActivityAlternativeCategory.RESTAURANT)));

        verify(sessionRepository, never()).save(any());
    }

    @Test
    void createAlternatives_previousAccommodationRejectsHotelCategoryBeforeAiCall() {
        Trip trip = trip(4, 0);
        Activity previousHotel = activity(trip);
        previousHotel.setId(30L);
        previousHotel.setType(ActivityType.ACCOMMODATION);
        previousHotel.setName("Nhan phong khach san");
        previousHotel.setDisplayOrder(0);

        Activity current = activity(trip);
        current.setType(ActivityType.FOOD);
        current.setDisplayOrder(1);
        stubCreateGraph(trip, current, List.of(previousHotel, current));

        assertThrows(AppException.class, () -> service.createAlternatives(
                USER_ID, TRIP_ID, DAY_ID, ACTIVITY_ID, alternativesRequest(ActivityAlternativeCategory.HOTEL)));

        verify(sessionRepository, never()).save(any());
    }

    @Test
    void createAlternatives_normalUser_throwsPremiumRequiredBeforeAiCall() {
        doThrow(AppException.premiumRequired()).when(entitlementService).requirePremium(USER_ID);

        AppException ex = assertThrows(AppException.class, () -> service.createAlternatives(
                USER_ID, TRIP_ID, DAY_ID, ACTIVITY_ID, alternativesRequest(ActivityAlternativeCategory.RESTAURANT)));

        assertEquals("PREMIUM_REQUIRED", ex.getCode());
        verify(sessionRepository, never()).save(any());
        verify(aiUsageService, never()).recordUsage(any(), any(), anyInt(), anyInt());
    }

    private void stubReplaceGraph(Trip trip, Activity activity, String optionsJson) {
        when(sessionRepository.findByIdAndUserIdAndTripIdAndDayIdAndActivityIdForUpdate(
                101L, USER_ID, TRIP_ID, DAY_ID, ACTIVITY_ID))
                .thenReturn(Optional.of(ActivityAlternativeSession.builder()
                        .userId(USER_ID)
                        .tripId(TRIP_ID)
                        .dayId(DAY_ID)
                        .activityId(ACTIVITY_ID)
                        .category(ActivityAlternativeCategory.RESTAURANT)
                        .status(ActivityAlternativeSessionStatus.PENDING)
                        .optionsJson(optionsJson)
                        .expiresAt(LocalDateTime.now().plusMinutes(5))
                        .build()));
        when(tripRepository.findByIdAndUserIdForUpdate(TRIP_ID, USER_ID)).thenReturn(Optional.of(trip));
        when(tripDayRepository.findByIdAndTripId(DAY_ID, TRIP_ID)).thenReturn(Optional.of(activity.getDay()));
        when(activityRepository.findByIdAndDayTripUserId(ACTIVITY_ID, USER_ID)).thenReturn(Optional.of(activity));
        when(activityRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubCreateGraph(Trip trip, Activity current, List<Activity> activities) {
        when(tripRepository.findByIdAndUserId(TRIP_ID, USER_ID)).thenReturn(Optional.of(trip));
        when(tripDayRepository.findByIdAndTripId(DAY_ID, TRIP_ID)).thenReturn(Optional.of(current.getDay()));
        when(activityRepository.findByIdAndDayTripUserId(ACTIVITY_ID, USER_ID)).thenReturn(Optional.of(current));
        when(activityRepository.findByTripIdWithDayOrderByDayAndOrder(TRIP_ID)).thenReturn(activities);
    }

    private ActivityAlternativeSession pendingSession(String optionsJson) {
        ActivityAlternativeSession session = ActivityAlternativeSession.builder()
                .userId(USER_ID)
                .tripId(TRIP_ID)
                .dayId(DAY_ID)
                .activityId(ACTIVITY_ID)
                .category(ActivityAlternativeCategory.RESTAURANT)
                .status(ActivityAlternativeSessionStatus.PENDING)
                .optionsJson(optionsJson)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
        session.setId(101L);
        return session;
    }

    private Trip trip(int freeLimit, int freeUsed) {
        Trip trip = Trip.builder()
                .title("Da Nang trip")
                .departure("Ha Noi")
                .destination("Da Nang")
                .dateStart(LocalDate.of(2026, 7, 1))
                .dateEnd(LocalDate.of(2026, 7, 3))
                .peopleCount(2)
                .budgetVnd(5_000_000L)
                .activitySwapFreeLimit(freeLimit)
                .activitySwapFreeUsed(freeUsed)
                .build();
        trip.setId(TRIP_ID);
        return trip;
    }

    private Activity activity(Trip trip) {
        TripDay day = TripDay.builder()
                .trip(trip)
                .dayNumber(1)
                .date(LocalDate.of(2026, 7, 1))
                .build();
        day.setId(DAY_ID);
        Activity activity = Activity.builder()
                .day(day)
                .startTime(LocalTime.of(12, 0))
                .name("Quan an cu")
                .description("An trua")
                .type(ActivityType.FOOD)
                .costVnd(100_000L)
                .displayOrder(1)
                .build();
        activity.setId(ACTIVITY_ID);
        return activity;
    }

    private ReplaceActivityRequest replaceRequest() {
        ReplaceActivityRequest request = new ReplaceActivityRequest();
        request.setSessionId(101L);
        request.setOptionId("opt-1");
        return request;
    }

    private ActivityAlternativesRequest alternativesRequest(ActivityAlternativeCategory category) {
        ActivityAlternativesRequest request = new ActivityAlternativesRequest();
        request.setCategory(category);
        request.setLimit(4);
        return request;
    }

    private String sessionJson() throws Exception {
        return new ObjectMapper().writeValueAsString(List.of(ActivityAlternativeOptionResponse.builder()
                .optionId("opt-1")
                .name("Quan an moi")
                .description("Gan lich trinh hon")
                .type(ActivityType.FOOD)
                .costVnd(120_000L)
                .searchQuery("Quan an moi Da Nang")
                .build()));
    }

    private String sessionJsonWithPlaceCache() throws Exception {
        return new ObjectMapper().writeValueAsString(List.of(ActivityAlternativeOptionResponse.builder()
                .optionId("opt-1")
                .name("Nha hang Taiga")
                .description("Gan lich trinh hon")
                .type(ActivityType.FOOD)
                .costVnd(120_000L)
                .searchQuery("Nha hang Taiga Da Nang")
                .placeCacheId(901L)
                .build()));
    }

    private PlaceCache placeCache() {
        PlaceCache cache = new PlaceCache();
        cache.setId(901L);
        cache.setName("Nha hang Taiga");
        cache.setSerpTitle("Thai Market Restaurant - Ba Na Hills");
        cache.setAddress("Ba Na Hills, Da Nang");
        cache.setPhotosJson("[{\"url\":\"https://cdn.example.com/full.jpg\",\"thumbnail\":\"https://cdn.example.com/thumb.jpg\"}]");
        return cache;
    }
}
