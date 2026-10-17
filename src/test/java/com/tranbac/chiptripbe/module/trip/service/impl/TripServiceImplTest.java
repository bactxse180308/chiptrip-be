package com.tranbac.chiptripbe.module.trip.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.common.enums.ActivityType;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.common.resilience.ProviderResilience;
import com.tranbac.chiptripbe.module.ai.dto.AiCallResult;
import com.tranbac.chiptripbe.module.ai.dto.AiItineraryResult;
import com.tranbac.chiptripbe.module.ai.service.AiService;
import com.tranbac.chiptripbe.module.ai.repository.AiUsageRepository;
import com.tranbac.chiptripbe.module.place.entity.PlaceCache;
import com.tranbac.chiptripbe.module.trip.entity.Activity;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.trip.entity.TripDay;
import com.tranbac.chiptripbe.module.place.repository.PlaceCacheRepository;
import com.tranbac.chiptripbe.module.place.service.PlaceEnrichmentService;
import com.tranbac.chiptripbe.module.trip.dto.request.GenerateTripRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.TripEnrichmentResultDto;
import com.tranbac.chiptripbe.module.trip.dto.response.TripGenerateResponse;
import com.tranbac.chiptripbe.module.trip.repository.ActivityAlternativeSessionRepository;
import com.tranbac.chiptripbe.module.trip.repository.ActivityRepository;
import com.tranbac.chiptripbe.module.trip.repository.ChecklistItemRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripCommentRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripDayRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripLikeRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripMemberRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.trip.service.TripGenerationPersistenceService;
import com.tranbac.chiptripbe.module.trip.service.TripMemberService;
import com.tranbac.chiptripbe.module.user.entity.User;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tier limits + credit precheck ở validateRequestAndCredits (chạy TRƯỚC khi gọi AI).
 * CREDIT_PREMIUM_SPEC.md Mục 5.5 + acceptance Mục 7.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TripServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @Mock private TripRepository tripRepository;
    @Mock private TripDayRepository tripDayRepository;
    @Mock private ActivityRepository activityRepository;
    @Mock private ActivityAlternativeSessionRepository activityAlternativeSessionRepository;
    @Mock private ChecklistItemRepository checklistItemRepository;
    @Mock private TripMemberRepository tripMemberRepository;
    @Mock private TripLikeRepository tripLikeRepository;
    @Mock private TripCommentRepository tripCommentRepository;
    @Mock private TripMemberService tripMemberService;
    @Mock private UserRepository userRepository;
    @Mock private AiService aiService;
    @Mock private AiUsageRepository aiUsageRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private PlaceEnrichmentService placeEnrichmentService;
    @Mock private PlaceCacheRepository placeCacheRepository;
    @Mock private ProviderResilience providerResilience;
    @Mock private TripGenerationPersistenceService tripGenerationPersistenceService;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private Executor enrichmentExecutor;
    @Mock private Executor backgroundEnrichmentExecutor;

    private TripServiceImpl service;

    @BeforeEach
    void runExecutorsSynchronously() {
        service = new TripServiceImpl(
                tripRepository,
                tripDayRepository,
                activityRepository,
                activityAlternativeSessionRepository,
                checklistItemRepository,
                tripMemberRepository,
                tripLikeRepository,
                tripCommentRepository,
                tripMemberService,
                userRepository,
                aiService,
                aiUsageRepository,
                objectMapper,
                placeEnrichmentService,
                placeCacheRepository,
                providerResilience,
                tripGenerationPersistenceService,
                messagingTemplate,
                enrichmentExecutor,
                backgroundEnrichmentExecutor);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(enrichmentExecutor).execute(any(Runnable.class));
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(backgroundEnrichmentExecutor).execute(any(Runnable.class));
        when(providerResilience.cooldownRemaining(any(String[].class))).thenReturn(Duration.ZERO);
        when(tripRepository.tryClaimEnrichment(
                anyLong(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        when(activityRepository.updateResolvedPlaceIfQueryUnchanged(
                anyLong(), anyString(), any(), any(), anyLong(), any(), any(), any(), any(), anyString()))
                .thenReturn(1);
    }

    @Test
    void generateTrip_normalUserExceedsMaxDays_throwsLimitExceededBeforeAi() {
        stubUser(normalUser());
        LocalDate start = LocalDate.now(VN);
        // 4 ngày > 3 (Normal)
        GenerateTripRequest req = request(start, start.plusDays(3), List.of("food_tour"));

        AppException ex = assertThrows(AppException.class, () -> service.generateTrip(USER_ID, req));

        assertEquals("LIMIT_EXCEEDED", ex.getCode());
        verify(aiService, never()).generateItinerary(any(), any());
    }

    @Test
    void generateTrip_normalUserTooManyStyles_throwsLimitExceeded() {
        stubUser(normalUser());
        LocalDate start = LocalDate.now(VN);
        // 6 styles > 5 (Normal), số ngày hợp lệ
        GenerateTripRequest req = request(start, start.plusDays(1),
                List.of("a", "b", "c", "d", "e", "f"));

        AppException ex = assertThrows(AppException.class, () -> service.generateTrip(USER_ID, req));

        assertEquals("LIMIT_EXCEEDED", ex.getCode());
        verify(aiService, never()).generateItinerary(any(), any());
    }

    @Test
    void generateTrip_normalUserNoPaidNoTrial_throwsDailyTrialUsed() {
        User user = normalUser();
        user.setTrialCreditBalance(0);
        user.setTrialCreditDate(LocalDate.now(VN));   // đã tiêu trial hôm nay
        stubUser(user);
        LocalDate start = LocalDate.now(VN);
        GenerateTripRequest req = request(start, start.plusDays(1), List.of("food_tour"));

        AppException ex = assertThrows(AppException.class, () -> service.generateTrip(USER_ID, req));

        assertEquals("DAILY_TRIAL_USED", ex.getCode());
        verify(aiService, never()).generateItinerary(any(), any());
    }

    @Test
    void generateTrip_partialPaidAndTrial_failsBeforeAiProviderCall() {
        User user = premiumUser();
        user.setAiCreditUnits(75);
        user.setTrialCreditBalance(1);
        user.setTrialCreditDate(LocalDate.now(VN));
        stubUser(user);
        LocalDate start = LocalDate.now(VN);
        GenerateTripRequest req = request(start, start.plusDays(1), List.of("food_tour"));

        assertEquals("DAILY_TRIAL_USED",
                assertThrows(AppException.class, () -> service.generateTrip(USER_ID, req)).getCode());
        verify(aiService, never()).generateItinerary(any(), any());
    }

    @Test
    void generateTrip_premiumUserWithin10Days_passesValidationAndReachesAi() {
        stubUser(premiumUser());   // paid > 0 → premium → maxDays 10
        LocalDate start = LocalDate.now(VN);
        GenerateTripRequest req = request(start, start.plusDays(9), List.of("a", "b", "c", "d", "e", "f", "g"));
        // Validation pass → gọi AI; stub AI ném marker để dừng sớm (không cần mock cả chain persist)
        when(aiService.generateItinerary(any(), any()))
                .thenThrow(new IllegalStateException("AI_REACHED"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.generateTrip(USER_ID, req));

        assertEquals("AI_REACHED", ex.getMessage());   // đã vượt qua limit/credit gate
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    @Test
    void publicTripDoesNotExposeOwnerOnlyFieldsOrExactDates() {
        Trip trip = privacyTrip();
        when(tripRepository.findById(77L)).thenReturn(Optional.of(trip));
        stubPrivacyItinerary(trip);
        var json = tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(service.getPublicTrip(77L));
        for (String key : List.of("shareToken", "members", "checklist", "dateStart", "dateEnd", "budgetVnd", "status")) {
            org.junit.jupiter.api.Assertions.assertFalse(json.has(key), "Public field leaked: " + key);
        }
        org.junit.jupiter.api.Assertions.assertFalse(json.path("user").has("email"));
        org.junit.jupiter.api.Assertions.assertFalse(json.path("days").get(0).has("date"));
        org.junit.jupiter.api.Assertions.assertFalse(json.path("days").get(0).path("activities").get(0).has("bookingUrl"));
        verify(tripMemberRepository, never()).findByTripIdWithUser(anyLong());
        verify(checklistItemRepository, never()).findByTripIdOrderByDisplayOrder(anyLong());
    }

    @Test
    void sharedTripUsesReadOnlyProjectionButRetainsScheduledDates() {
        Trip trip = privacyTrip();
        String token = TripShareTokens.create();
        trip.setShareToken(token);
        stubPrivacyItinerary(trip);
        when(tripRepository.findByShareToken(token)).thenReturn(Optional.of(trip));
        var json = tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(service.getSharedTrip(token));
        for (String key : List.of("shareToken", "members", "checklist")) {
            org.junit.jupiter.api.Assertions.assertFalse(json.has(key), "Shared field leaked: " + key);
        }
        org.junit.jupiter.api.Assertions.assertFalse(json.path("user").has("email"));
        assertTrue(json.hasNonNull("dateStart"));
        assertTrue(json.path("days").get(0).hasNonNull("date"));
    }

    @Test
    void shareRotationAndRevocationRemainOwnerOnly() {
        Trip trip = privacyTrip();
        when(tripRepository.findById(77L)).thenReturn(Optional.of(trip));
        assertThrows(AppException.class, () -> service.enableShare(2L, 77L));
        String first = service.enableShare(USER_ID, 77L).getShareToken();
        assertTrue(TripShareTokens.isCurrent(first));
        String second = service.enableShare(USER_ID, 77L).getShareToken();
        org.junit.jupiter.api.Assertions.assertNotEquals(first, second);
        assertEquals(second, trip.getShareToken());
        service.disableShare(USER_ID, 77L);
        org.junit.jupiter.api.Assertions.assertNull(trip.getShareToken());
    }

    @Test
    void privateTripIsNotPublicAndOwnerDetailRetainsOwnerFields() {
        Trip trip = privacyTrip();
        trip.setPublic(false);
        trip.setShareToken(TripShareTokens.create());
        when(tripRepository.findById(77L)).thenReturn(Optional.of(trip));
        assertThrows(AppException.class, () -> service.getPublicTrip(77L));
        var owner = service.getTripDetail(USER_ID, 77L);
        assertEquals(trip.getShareToken(), owner.getShareToken());
        assertEquals(trip.getDateStart(), owner.getDateStart());
        assertEquals("private@example.com", owner.getUser().getEmail());
        assertThrows(AppException.class, () -> service.getTripDetail(2L, 77L));
    }

    private void stubPrivacyItinerary(Trip trip) {
        var day = TripDay.builder().trip(trip).dayNumber(1).date(trip.getDateStart()).build();
        day.setId(3L);
        var activity = Activity.builder().day(day).name("Visit").costVnd(10000L)
                .bookingUrl("https://example.com/private-quote").build();
        when(tripDayRepository.findByTripIdOrderByDayNumber(77L)).thenReturn(List.of(day));
        when(activityRepository.findByTripIdWithDayOrderByDayAndOrder(77L)).thenReturn(List.of(activity));
    }

    @Test
    void legacyShareTokensAreRejectedWithoutDatabaseLookup() {
        assertThrows(AppException.class, () -> service.getSharedTrip("0123456789abcdef0123456789abcdef"));
        verify(tripRepository, never()).findByShareToken(anyString());
    }

    @Test
    void publicCloneDoesNotCopyPrivateChecklistDatesBudgetOrBookingUrl() {
        Trip source = privacyTrip();
        source.setBudgetVnd(9999999L);
        var day = TripDay.builder().trip(source).dayNumber(1).date(source.getDateStart()).build();
        day.setId(3L);
        var activity = Activity.builder().day(day).name("Visit").bookingUrl("https://example.com/private-quote").build();
        day.setActivities(List.of(activity));
        source.setDays(List.of(day));
        source.setChecklist(List.of(com.tranbac.chiptripbe.module.trip.entity.ChecklistItem.builder().name("Private note").build()));
        User cloner = normalUser();
        cloner.setId(2L);
        when(tripRepository.findById(77L)).thenReturn(Optional.of(source));
        when(userRepository.findById(2L)).thenReturn(Optional.of(cloner));
        when(tripRepository.save(any())).thenAnswer(inv -> {
            Trip copy = inv.getArgument(0); copy.setId(88L); return copy;
        });
        when(tripDayRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.cloneTrip(2L, 77L);
        var clone = ArgumentCaptor.forClass(Trip.class);
        verify(tripRepository).save(clone.capture());
        assertEquals(LocalDate.now(VN), clone.getValue().getDateStart());
        assertEquals(0L, clone.getValue().getBudgetVnd());
        var copiedActivity = ArgumentCaptor.forClass(Activity.class);
        verify(activityRepository).save(copiedActivity.capture());
        org.junit.jupiter.api.Assertions.assertNull(copiedActivity.getValue().getBookingUrl());
        verify(checklistItemRepository, never()).save(any());
    }

    private Trip privacyTrip() {
        User owner = normalUser();
        owner.setEmail("private@example.com");
        Trip trip = Trip.builder().user(owner).title("Public itinerary").destination("Da Nang")
                .isPublic(true).shareToken("secret").dateStart(LocalDate.of(2026, 10, 1))
                .dateEnd(LocalDate.of(2026, 10, 3)).build();
        trip.setId(77L);
        return trip;
    }

    @Test
    void generateTrip_foregroundResolveAlwaysDisablesScraper() {
        LocalDate start = LocalDate.now(VN);
        GenerateTripRequest req = request(start, start.plusDays(1), List.of("food_tour"));
        AiItineraryResult itinerary = itinerary(start,
                List.of(activity("Day 1 food", "FOOD", "day-1-food")),
                List.of(activity("Day 2 attraction", "ATTRACTION", "day-2-attraction")));
        stubSuccessfulGenerate(req, itinerary);
        whenForegroundResolve("day-1-food", PlaceEnrichmentService.EnrichmentDepth.FULL)
                .thenReturn(Optional.of(cache(true)));
        whenForegroundResolve("day-2-attraction", PlaceEnrichmentService.EnrichmentDepth.BASIC)
                .thenReturn(Optional.of(cache(true)));
        whenBackgroundResolve("day-2-attraction")
                .thenReturn(Optional.of(cache(true)));

        service.generateTrip(USER_ID, req);

        verify(placeEnrichmentService, times(2)).resolvePlace(
                anyString(), anyString(), anyList(), any(Instant.class), anyBoolean(),
                any(PlaceEnrichmentService.EnrichmentDepth.class), eq(false), eq(false));
        verify(placeEnrichmentService, never()).resolvePlace(
                anyString(), anyString(), anyList(), any(Instant.class), anyBoolean(),
                any(PlaceEnrichmentService.EnrichmentDepth.class), anyBoolean(), eq(true));
    }

    @Test
    void generateTrip_serpMissDay1AddsFullGroupToBackground() {
        LocalDate start = LocalDate.now(VN);
        String day1Query = "day-1-food";
        GenerateTripRequest req = request(start, start, List.of("food_tour"));
        AiItineraryResult itinerary = itinerary(start,
                List.of(activity("Day 1 food", "FOOD", day1Query)));
        stubSuccessfulGenerate(req, itinerary);
        whenForegroundResolve(day1Query, PlaceEnrichmentService.EnrichmentDepth.FULL)
                .thenReturn(Optional.of(cache(false)));
        whenBackgroundResolve(day1Query)
                .thenReturn(Optional.of(cache(true)));

        service.generateTrip(USER_ID, req);

        verify(backgroundEnrichmentExecutor).execute(any(Runnable.class));
        verify(placeEnrichmentService).resolvePlace(
                eq(day1Query), anyString(), anyList(), isNull(), eq(true),
                eq(PlaceEnrichmentService.EnrichmentDepth.FULL), eq(false));
    }

    @Test
    void generateTrip_serpHealthyFullGroupDoesNotEnterBackground() {
        LocalDate start = LocalDate.now(VN);
        String day1Query = "day-1-food";
        String day2Query = "day-2-food";
        GenerateTripRequest req = request(start, start.plusDays(1), List.of("food_tour"));
        AiItineraryResult itinerary = itinerary(start,
                List.of(activity("Day 1 food", "FOOD", day1Query)),
                List.of(activity("Day 2 food", "FOOD", day2Query)));
        stubSuccessfulGenerate(req, itinerary);
        whenForegroundResolve(day1Query, PlaceEnrichmentService.EnrichmentDepth.FULL)
                .thenReturn(Optional.of(cache(true)));
        whenForegroundResolve(day2Query, PlaceEnrichmentService.EnrichmentDepth.BASIC)
                .thenReturn(Optional.of(cache(false)));
        whenBackgroundResolve(day2Query)
                .thenReturn(Optional.of(cache(true)));

        service.generateTrip(USER_ID, req);

        verify(placeEnrichmentService, never()).resolvePlace(
                eq(day1Query), anyString(), anyList(), isNull(), anyBoolean(),
                eq(PlaceEnrichmentService.EnrichmentDepth.FULL), anyBoolean());
        verify(placeEnrichmentService).resolvePlace(
                eq(day2Query), anyString(), anyList(), isNull(), eq(true),
                eq(PlaceEnrichmentService.EnrichmentDepth.FULL), eq(false));
    }

    @Test
    void generateTrip_basicDay2AlwaysEntersBackgroundEvenWhenForegroundCacheIsEnriched() {
        LocalDate start = LocalDate.now(VN);
        String day1Query = "day-1-food";
        String day2Query = "day-2-food";
        GenerateTripRequest req = request(start, start.plusDays(1), List.of("food_tour"));
        AiItineraryResult itinerary = itinerary(start,
                List.of(activity("Day 1 food", "FOOD", day1Query)),
                List.of(activity("Day 2 food", "FOOD", day2Query)));
        stubSuccessfulGenerate(req, itinerary);
        whenForegroundResolve(day1Query, PlaceEnrichmentService.EnrichmentDepth.FULL)
                .thenReturn(Optional.of(cache(true)));
        whenForegroundResolve(day2Query, PlaceEnrichmentService.EnrichmentDepth.BASIC)
                .thenReturn(Optional.of(cache(true)));
        whenBackgroundResolve(day2Query)
                .thenReturn(Optional.of(cache(true)));

        service.generateTrip(USER_ID, req);

        verify(placeEnrichmentService).resolvePlace(
                eq(day2Query), anyString(), anyList(), isNull(), eq(true),
                eq(PlaceEnrichmentService.EnrichmentDepth.FULL), eq(false));
    }

    @Test
    void generateTrip_oneDayFullyEnrichedDoesNotSubmitBackgroundJob() {
        LocalDate start = LocalDate.now(VN);
        String day1Query = "day-1-food";
        GenerateTripRequest req = request(start, start, List.of("food_tour"));
        AiItineraryResult itinerary = itinerary(start,
                List.of(activity("Day 1 food", "FOOD", day1Query)));
        stubSuccessfulGenerate(req, itinerary);
        whenForegroundResolve(day1Query, PlaceEnrichmentService.EnrichmentDepth.FULL)
                .thenReturn(Optional.of(cache(true)));

        service.generateTrip(USER_ID, req);

        verify(backgroundEnrichmentExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    void generateTrip_backgroundStillIncompleteKeepsEnrichmentPending() {
        LocalDate start = LocalDate.now(VN);
        String query = "day-1-food";
        GenerateTripRequest req = request(start, start, List.of("food_tour"));
        AiItineraryResult itinerary = itinerary(start,
                List.of(activity("Day 1 food", "FOOD", query)));
        stubSuccessfulGenerate(req, itinerary);
        whenForegroundResolve(query, PlaceEnrichmentService.EnrichmentDepth.FULL)
                .thenReturn(Optional.of(cache(false)));
        whenBackgroundResolve(query).thenReturn(Optional.of(cache(false)));
        LocalDateTime before = LocalDateTime.now();

        service.generateTrip(USER_ID, req);

        verify(tripRepository, never()).markEnrichmentDone(99L);
        ArgumentCaptor<LocalDateTime> retryAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tripRepository).scheduleEnrichmentRetry(eq(99L), retryAt.capture());
        assertTrue(retryAt.getValue().isAfter(before.plusMinutes(4)));
        assertTrue(retryAt.getValue().isBefore(LocalDateTime.now().plusMinutes(6)));
        verify(messagingTemplate, never()).convertAndSendToUser(
                "1", "/queue/trip-enrichment", TripEnrichmentResultDto.done(99L));
    }

    @Test
    void generateTrip_delayedBackgroundUsesPersistedAttemptsForBackoff() {
        LocalDate start = LocalDate.now(VN);
        String query = "delayed-background-place";
        GenerateTripRequest req = request(start, start, List.of("food_tour"));
        AiItineraryResult itinerary = itinerary(start,
                List.of(activity("Delayed background place", "FOOD", query)));
        stubSuccessfulGenerate(req, itinerary);
        whenForegroundResolve(query, PlaceEnrichmentService.EnrichmentDepth.FULL)
                .thenReturn(Optional.of(cache(false)));
        whenBackgroundResolve(query).thenReturn(Optional.of(cache(false)));
        when(tripRepository.findEnrichmentAttemptsById(99L)).thenReturn(Optional.of(5));
        LocalDateTime before = LocalDateTime.now();

        service.generateTrip(USER_ID, req);

        ArgumentCaptor<LocalDateTime> retryAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tripRepository).scheduleEnrichmentRetry(eq(99L), retryAt.capture());
        assertTrue(retryAt.getValue().isAfter(before.plusMinutes(59)));
        assertTrue(retryAt.getValue().isBefore(LocalDateTime.now().plusMinutes(61)));
    }

    @Test
    void generateTrip_activeProviderCooldownDefersWithoutBackgroundAttempt() {
        LocalDate start = LocalDate.now(VN);
        String query = "day-1-food";
        GenerateTripRequest req = request(start, start, List.of("food_tour"));
        AiItineraryResult itinerary = itinerary(start,
                List.of(activity("Day 1 food", "FOOD", query)));
        stubSuccessfulGenerate(req, itinerary);
        whenForegroundResolve(query, PlaceEnrichmentService.EnrichmentDepth.FULL)
                .thenReturn(Optional.of(cache(false)));
        when(providerResilience.cooldownRemaining(any(String[].class)))
                .thenReturn(Duration.ofMinutes(20));
        LocalDateTime before = LocalDateTime.now();

        service.generateTrip(USER_ID, req);

        ArgumentCaptor<LocalDateTime> retryAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tripRepository).deferEnrichmentRetry(eq(99L), retryAt.capture());
        assertTrue(retryAt.getValue().isAfter(before.plusMinutes(19)));
        assertTrue(retryAt.getValue().isBefore(LocalDateTime.now().plusMinutes(21)));
        verify(tripRepository, never()).scheduleEnrichmentRetry(anyLong(), any(LocalDateTime.class));
        verify(placeEnrichmentService, never()).resolvePlace(
                eq(query), anyString(), anyList(), isNull(), anyBoolean(),
                eq(PlaceEnrichmentService.EnrichmentDepth.FULL), anyBoolean());
        verify(tripRepository, never()).markEnrichmentDone(99L);
    }

    @Test
    void generateTrip_backgroundClaimRejectedDoesNotRunStaleJob() {
        LocalDate start = LocalDate.now(VN);
        String query = "stale-background-place";
        GenerateTripRequest req = request(start, start, List.of("food_tour"));
        AiItineraryResult itinerary = itinerary(start,
                List.of(activity("Stale background place", "FOOD", query)));
        stubSuccessfulGenerate(req, itinerary);
        whenForegroundResolve(query, PlaceEnrichmentService.EnrichmentDepth.FULL)
                .thenReturn(Optional.of(cache(false)));
        when(tripRepository.tryClaimEnrichment(eq(99L),
                any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(0);

        service.generateTrip(USER_ID, req);

        verify(placeEnrichmentService, never()).resolvePlace(
                eq(query), anyString(), anyList(), isNull(), anyBoolean(),
                eq(PlaceEnrichmentService.EnrichmentDepth.FULL), anyBoolean());
        verify(tripRepository, never()).deferEnrichmentRetry(anyLong(), any(LocalDateTime.class));
        verify(tripRepository, never()).scheduleEnrichmentRetry(anyLong(), any(LocalDateTime.class));
        verify(tripRepository, never()).markEnrichmentDone(anyLong());
    }

    @Test
    void generateTrip_backgroundResolveLinksPreviouslyUnresolvedActivityBeforeDone() {
        LocalDate start = LocalDate.now(VN);
        String query = "unresolved-place";
        GenerateTripRequest req = request(start, start, List.of("food_tour"));
        AiItineraryResult itinerary = itinerary(start,
                List.of(activity("Unresolved place", "FOOD", query)));
        stubSuccessfulGenerate(req, itinerary);
        whenForegroundResolve(query, PlaceEnrichmentService.EnrichmentDepth.FULL)
                .thenReturn(Optional.empty());

        Activity persistedActivity = Activity.builder()
                .type(ActivityType.FOOD)
                .searchQuery(query)
                .build();
        persistedActivity.setId(31L);
        when(activityRepository.findByDayTripIdOrderByDayDayNumberAscDisplayOrderAsc(99L))
                .thenReturn(List.of(persistedActivity));
        PlaceCache resolved = cache(true);
        resolved.setId(17L);
        resolved.setLatitude(BigDecimal.valueOf(10.77));
        resolved.setLongitude(BigDecimal.valueOf(106.69));
        resolved.setGoongPlaceId("goong-17");
        resolved.setAddress("Resolved address");
        whenBackgroundResolve(query).thenReturn(Optional.of(resolved));

        service.generateTrip(USER_ID, req);

        verify(activityRepository).updateResolvedPlaceIfQueryUnchanged(
                31L, query, null, null, 17L,
                resolved.getLatitude(), resolved.getLongitude(),
                "goong-17", "Resolved address", "goong");
        verify(tripRepository).markEnrichmentDone(99L);
    }

    @Test
    void generateTrip_concurrentSearchQueryEditKeepsEnrichmentPending() {
        LocalDate start = LocalDate.now(VN);
        String query = "place-edited-while-enriching";
        GenerateTripRequest req = request(start, start, List.of("food_tour"));
        AiItineraryResult itinerary = itinerary(start,
                List.of(activity("Concurrent edit", "FOOD", query)));
        stubSuccessfulGenerate(req, itinerary);
        whenForegroundResolve(query, PlaceEnrichmentService.EnrichmentDepth.FULL)
                .thenReturn(Optional.empty());

        Activity persistedActivity = Activity.builder().type(ActivityType.FOOD).searchQuery(query).build();
        persistedActivity.setId(37L);
        when(activityRepository.findByDayTripIdOrderByDayDayNumberAscDisplayOrderAsc(99L))
                .thenReturn(List.of(persistedActivity));
        PlaceCache resolved = cache(true);
        resolved.setId(20L);
        whenBackgroundResolve(query).thenReturn(Optional.of(resolved));
        when(activityRepository.updateResolvedPlaceIfQueryUnchanged(
                eq(37L), eq(query), any(), any(), eq(20L), any(), any(), any(), any(), anyString()))
                .thenReturn(0);

        service.generateTrip(USER_ID, req);

        verify(tripRepository, never()).markEnrichmentDone(99L);
        verify(tripRepository).scheduleEnrichmentRetry(eq(99L), any(LocalDateTime.class));
    }

    @Test
    void generateTrip_backgroundResolveRetriesAccommodationDetailsBeforeDone() {
        LocalDate start = LocalDate.now(VN);
        String query = "unresolved-hotel";
        GenerateTripRequest req = request(start, start, List.of("relax"));
        AiItineraryResult itinerary = itinerary(start,
                List.of(activity("Unresolved hotel", "ACCOMMODATION", query)));
        stubSuccessfulGenerate(req, itinerary);
        whenForegroundResolve(query, PlaceEnrichmentService.EnrichmentDepth.FULL)
                .thenReturn(Optional.empty());

        Activity persistedActivity = Activity.builder()
                .type(ActivityType.ACCOMMODATION)
                .searchQuery(query)
                .build();
        persistedActivity.setId(32L);
        when(activityRepository.findByDayTripIdOrderByDayDayNumberAscDisplayOrderAsc(99L))
                .thenReturn(List.of(persistedActivity));
        PlaceCache resolved = cache(true);
        resolved.setId(18L);
        whenBackgroundResolve(query).thenReturn(Optional.of(resolved));

        service.generateTrip(USER_ID, req);

        verify(placeEnrichmentService).enrichAccommodation(
                resolved, start, start.plusDays(1), 2, null);
        verify(tripRepository).markEnrichmentDone(99L);
    }

    @Test
    void sweepPendingEnrichments_reEnrichesNotFullPlaceThenMarksDone() {
        // P1 durable: trip còn pending → sweeper reconstruct từ DB, enrich lại thành công → tắt cờ.
        User owner = normalUser();
        Trip trip = Trip.builder().user(owner).destination("Đà Lạt").enrichmentPending(true).build();
        trip.setId(77L);
        when(tripRepository.findPendingEnrichment(any(LocalDateTime.class),
                any(LocalDateTime.class), any()))
                .thenReturn(List.of(trip));

        TripDay day = TripDay.builder().dayNumber(1).build();
        day.setId(11L);
        when(tripDayRepository.findByTripIdOrderByDayNumber(77L)).thenReturn(List.of(day));

        Activity act = Activity.builder()
                .type(ActivityType.FOOD)
                .searchQuery("Cafe Tùng Đà Lạt")
                .placeCacheId(7L)
                .build();
        act.setId(33L);
        when(activityRepository.findByDayIdOrderByDisplayOrder(11L)).thenReturn(List.of(act));

        PlaceCache basic = cache(false);   // BASIC/serp-miss → cần enrich lại
        basic.setId(7L);
        when(placeCacheRepository.findAllById(anyList())).thenReturn(List.of(basic));
        when(placeEnrichmentService.canonicalKey("Cafe Tùng Đà Lạt")).thenReturn("cafe tung da lat");
        PlaceCache enriched = cache(true);
        enriched.setId(7L);
        whenBackgroundResolve("Cafe Tùng Đà Lạt").thenReturn(Optional.of(enriched));

        service.sweepPendingEnrichments();

        verify(tripRepository).markEnrichmentDone(77L);
        verify(messagingTemplate).convertAndSendToUser(
                "1", "/queue/trip-enrichment", TripEnrichmentResultDto.done(77L));
        verify(tripRepository, never()).scheduleEnrichmentRetry(anyLong(), any(LocalDateTime.class));
    }

    @Test
    void sweepPendingEnrichments_activeProviderCooldownDefersWithoutConsumingAttempt() {
        User owner = normalUser();
        Trip trip = Trip.builder().user(owner).destination("Da Lat").enrichmentPending(true).build();
        trip.setId(78L);
        when(tripRepository.findPendingEnrichment(any(LocalDateTime.class),
                any(LocalDateTime.class), any())).thenReturn(List.of(trip));
        when(providerResilience.cooldownRemaining(any(String[].class)))
                .thenReturn(Duration.ofSeconds(90));
        LocalDateTime before = LocalDateTime.now();

        service.sweepPendingEnrichments();

        ArgumentCaptor<LocalDateTime> retryAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tripRepository).deferEnrichmentRetry(eq(78L), retryAt.capture());
        assertTrue(retryAt.getValue().isAfter(before.plusSeconds(89)));
        assertTrue(retryAt.getValue().isBefore(LocalDateTime.now().plusSeconds(91)));
        verify(tripDayRepository, never()).findByTripIdOrderByDayNumber(78L);
        verify(tripRepository, never()).scheduleEnrichmentRetry(anyLong(), any(LocalDateTime.class));
        verify(tripRepository, never()).markEnrichmentDone(anyLong());
    }

    @Test
    void sweepPendingEnrichments_claimRejectedSkipsStaleSelection() {
        User owner = normalUser();
        Trip trip = Trip.builder().user(owner).destination("Da Lat").enrichmentPending(true).build();
        trip.setId(81L);
        when(tripRepository.findPendingEnrichment(any(LocalDateTime.class),
                any(LocalDateTime.class), any())).thenReturn(List.of(trip));
        when(tripRepository.tryClaimEnrichment(eq(81L),
                any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(0);

        service.sweepPendingEnrichments();

        verify(tripDayRepository, never()).findByTripIdOrderByDayNumber(81L);
        verify(tripRepository, never()).deferEnrichmentRetry(anyLong(), any(LocalDateTime.class));
        verify(tripRepository, never()).scheduleEnrichmentRetry(anyLong(), any(LocalDateTime.class));
        verify(tripRepository, never()).markEnrichmentDone(anyLong());
    }

    @Test
    void sweepPendingEnrichments_incompleteSecondAttemptSchedulesTenMinuteBackoff() {
        stubIncompletePendingTrip(79L, 1, 12L, 34L, 8L, "Retry place");
        LocalDateTime before = LocalDateTime.now();

        service.sweepPendingEnrichments();

        ArgumentCaptor<LocalDateTime> retryAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tripRepository).scheduleEnrichmentRetry(eq(79L), retryAt.capture());
        assertTrue(retryAt.getValue().isAfter(before.plusMinutes(9)));
        assertTrue(retryAt.getValue().isBefore(LocalDateTime.now().plusMinutes(11)));
        verify(tripRepository, never()).markEnrichmentDone(79L);
    }

    @Test
    void sweepPendingEnrichments_manyFailuresCapsBackoffAtSixtyMinutes() {
        stubIncompletePendingTrip(82L, 5, 14L, 35L, 9L, "Long retry place");
        LocalDateTime before = LocalDateTime.now();

        service.sweepPendingEnrichments();

        ArgumentCaptor<LocalDateTime> retryAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tripRepository).scheduleEnrichmentRetry(eq(82L), retryAt.capture());
        assertTrue(retryAt.getValue().isAfter(before.plusMinutes(59)));
        assertTrue(retryAt.getValue().isBefore(LocalDateTime.now().plusMinutes(61)));
        verify(tripRepository, never()).markEnrichmentDone(82L);
    }

    @Test
    void sweepPendingEnrichments_resolvesAndLinksActivityWithoutPlaceCache() {
        User owner = normalUser();
        Trip trip = Trip.builder().user(owner).destination("Da Lat").enrichmentPending(true).build();
        trip.setId(80L);
        when(tripRepository.findPendingEnrichment(any(LocalDateTime.class),
                any(LocalDateTime.class), any())).thenReturn(List.of(trip));

        TripDay day = TripDay.builder().dayNumber(1).build();
        day.setId(13L);
        when(tripDayRepository.findByTripIdOrderByDayNumber(80L)).thenReturn(List.of(day));
        Activity activity = Activity.builder()
                .type(ActivityType.ATTRACTION)
                .searchQuery("Missing coordinate")
                .build();
        activity.setId(32L);
        when(activityRepository.findByDayIdOrderByDisplayOrder(13L)).thenReturn(List.of(activity));
        when(placeEnrichmentService.canonicalKey("Missing coordinate"))
                .thenReturn("missing coordinate");
        PlaceCache resolved = cache(true);
        resolved.setId(18L);
        resolved.setLatitude(BigDecimal.valueOf(11.94));
        resolved.setLongitude(BigDecimal.valueOf(108.45));
        resolved.setAddress("Da Lat address");
        whenBackgroundResolve("Missing coordinate").thenReturn(Optional.of(resolved));

        service.sweepPendingEnrichments();

        verify(activityRepository).updateResolvedPlaceIfQueryUnchanged(
                32L, "Missing coordinate", null, null, 18L,
                resolved.getLatitude(), resolved.getLongitude(),
                null, "Da Lat address", "serpapi");
        verify(tripRepository).markEnrichmentDone(80L);
    }

    @Test
    void sweepPendingEnrichments_retriesAccommodationWithPersistedDateRange() {
        LocalDate stayDate = LocalDate.now(VN).plusDays(2);
        Trip trip = Trip.builder()
                .user(normalUser())
                .destination("Da Lat")
                .dateStart(stayDate)
                .dateEnd(stayDate.plusDays(1))
                .peopleCount(3)
                .enrichmentPending(true)
                .build();
        trip.setId(83L);
        when(tripRepository.findPendingEnrichment(any(LocalDateTime.class),
                any(LocalDateTime.class), any())).thenReturn(List.of(trip));

        TripDay day = TripDay.builder().dayNumber(1).date(stayDate).build();
        day.setId(15L);
        when(tripDayRepository.findByTripIdOrderByDayNumber(83L)).thenReturn(List.of(day));
        Activity activity = Activity.builder()
                .type(ActivityType.ACCOMMODATION)
                .searchQuery("Retry hotel")
                .build();
        activity.setId(36L);
        when(activityRepository.findByDayIdOrderByDisplayOrder(15L)).thenReturn(List.of(activity));
        when(placeEnrichmentService.canonicalKey("Retry hotel")).thenReturn("retry hotel");
        PlaceCache resolved = cache(true);
        resolved.setId(19L);
        whenBackgroundResolve("Retry hotel").thenReturn(Optional.of(resolved));

        service.sweepPendingEnrichments();

        verify(placeEnrichmentService).enrichAccommodation(
                resolved, stayDate, stayDate.plusDays(1), 3, null);
        verify(tripRepository).markEnrichmentDone(83L);
    }

    @Test
    void getTripDetail_loadsActivitiesOnceForAllDays() {
        User owner = normalUser();
        LocalDate start = LocalDate.now(VN);
        Trip trip = Trip.builder()
                .user(owner)
                .destination("Đà Lạt")
                .dateStart(start)
                .dateEnd(start.plusDays(1))
                .build();
        trip.setId(77L);

        TripDay day1 = TripDay.builder().trip(trip).dayNumber(1).date(start).build();
        day1.setId(11L);
        TripDay day2 = TripDay.builder().trip(trip).dayNumber(2).date(start.plusDays(1)).build();
        day2.setId(12L);
        Activity first = Activity.builder().day(day1).name("Hồ Xuân Hương").displayOrder(1).build();
        first.setId(101L);
        Activity second = Activity.builder().day(day2).name("Đồi chè").displayOrder(1).build();
        second.setId(102L);

        when(tripRepository.findById(77L)).thenReturn(Optional.of(trip));
        when(tripDayRepository.findByTripIdOrderByDayNumber(77L)).thenReturn(List.of(day1, day2));
        when(checklistItemRepository.findByTripIdOrderByDisplayOrder(77L)).thenReturn(List.of());
        when(activityRepository.findByTripIdWithDayOrderByDayAndOrder(77L)).thenReturn(List.of(first, second));
        when(tripMemberRepository.findByTripIdWithUser(77L)).thenReturn(List.of());

        var response = service.getTripDetail(USER_ID, 77L);

        assertEquals(2, response.getDays().size());
        assertEquals("Hồ Xuân Hương", response.getDays().get(0).getActivities().get(0).getName());
        assertEquals("Đồi chè", response.getDays().get(1).getActivities().get(0).getName());
        verify(activityRepository, times(1)).findByTripIdWithDayOrderByDayAndOrder(77L);
        verify(activityRepository, never()).findByDayIdOrderByDisplayOrder(anyLong());
    }

    private void stubUser(User user) {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    private User normalUser() {
        User user = User.builder().email("n@e.com").fullName("N").aiCredits(0).aiCreditUnits(0).build();
        user.setId(USER_ID);
        return user;
    }

    private User premiumUser() {
        User user = User.builder().email("p@e.com").fullName("P").aiCredits(0).aiCreditUnits(300).build();
        user.setId(USER_ID);
        return user;
    }

    private GenerateTripRequest request(LocalDate start, LocalDate end, List<String> styles) {
        GenerateTripRequest req = org.mockito.Mockito.mock(GenerateTripRequest.class);
        when(req.getStartDate()).thenReturn(start);
        when(req.getEndDate()).thenReturn(end);
        when(req.getStyles()).thenReturn(styles);
        when(req.getDeparture()).thenReturn("Hà Nội");
        when(req.getDestination()).thenReturn("Đà Lạt");
        when(req.getPeopleCount()).thenReturn(2);
        when(req.getBudgetVnd()).thenReturn(5_000_000L);
        return req;
    }

    private void stubIncompletePendingTrip(Long tripId, int attempts, Long dayId,
                                           Long activityId, Long cacheId, String query) {
        Trip trip = Trip.builder()
                .user(normalUser())
                .destination("Da Lat")
                .enrichmentPending(true)
                .enrichmentAttempts(attempts)
                .build();
        trip.setId(tripId);
        when(tripRepository.findPendingEnrichment(any(LocalDateTime.class),
                any(LocalDateTime.class), any())).thenReturn(List.of(trip));

        TripDay day = TripDay.builder().dayNumber(1).build();
        day.setId(dayId);
        when(tripDayRepository.findByTripIdOrderByDayNumber(tripId)).thenReturn(List.of(day));
        Activity activity = Activity.builder()
                .type(ActivityType.FOOD)
                .searchQuery(query)
                .placeCacheId(cacheId)
                .build();
        activity.setId(activityId);
        when(activityRepository.findByDayIdOrderByDisplayOrder(dayId)).thenReturn(List.of(activity));
        PlaceCache basic = cache(false);
        basic.setId(cacheId);
        when(placeCacheRepository.findAllById(anyList())).thenReturn(List.of(basic));
        when(placeEnrichmentService.canonicalKey(query)).thenReturn(query.toLowerCase());
        PlaceCache incomplete = cache(false);
        incomplete.setId(cacheId);
        whenBackgroundResolve(query).thenReturn(Optional.of(incomplete));
    }

    private void stubSuccessfulGenerate(GenerateTripRequest req, AiItineraryResult itinerary) {
        stubUser(normalUser());
        AiCallResult aiResult = new AiCallResult(itinerary, 10, 20);
        when(aiService.generateItinerary(eq(req), isNull())).thenReturn(aiResult);
        when(placeEnrichmentService.geocodeAnchor(anyString())).thenReturn(Optional.empty());
        when(placeEnrichmentService.canonicalKey(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(tripGenerationPersistenceService.persistGeneratedTrip(eq(USER_ID), eq(req), eq(aiResult), anyMap(), anyBoolean()))
                .thenReturn(TripGenerateResponse.builder().id(99L).build());
    }

    @SafeVarargs
    private final AiItineraryResult itinerary(LocalDate start, List<AiItineraryResult.AiActivity>... activitiesByDay) {
        AiItineraryResult result = new AiItineraryResult();
        result.setTitle("Generated trip");
        List<AiItineraryResult.AiDay> days = new java.util.ArrayList<>();
        for (int i = 0; i < activitiesByDay.length; i++) {
            AiItineraryResult.AiDay day = new AiItineraryResult.AiDay();
            day.setDayNumber(i + 1);
            day.setDate(start.plusDays(i).toString());
            day.setActivities(activitiesByDay[i]);
            days.add(day);
        }
        result.setDays(days);
        return result;
    }

    private AiItineraryResult.AiActivity activity(String name, String type, String searchQuery) {
        AiItineraryResult.AiActivity activity = new AiItineraryResult.AiActivity();
        activity.setTime("09:00");
        activity.setName(name);
        activity.setDescription(name);
        activity.setType(type);
        activity.setCostVnd(0L);
        activity.setSearchQuery(searchQuery);
        return activity;
    }

    private PlaceCache cache(boolean serpEnriched) {
        PlaceCache cache = new PlaceCache();
        cache.setName("cache");
        cache.setSerpEnriched(serpEnriched);
        return cache;
    }

    private org.mockito.stubbing.OngoingStubbing<Optional<PlaceCache>> whenForegroundResolve(
            String query, PlaceEnrichmentService.EnrichmentDepth depth) {
        return when(placeEnrichmentService.resolvePlace(
                eq(query), anyString(), anyList(), any(Instant.class), anyBoolean(), eq(depth), eq(false), eq(false)));
    }

    private org.mockito.stubbing.OngoingStubbing<Optional<PlaceCache>> whenBackgroundResolve(String query) {
        return when(placeEnrichmentService.resolvePlace(
                eq(query), anyString(), anyList(), isNull(), anyBoolean(),
                eq(PlaceEnrichmentService.EnrichmentDepth.FULL), anyBoolean()));
    }
}
