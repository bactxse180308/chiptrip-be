package com.tranbac.chiptripbe.module.trip.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.ai.service.AiService;
import com.tranbac.chiptripbe.module.ai.repository.AiUsageRepository;
import com.tranbac.chiptripbe.module.place.repository.PlaceCacheRepository;
import com.tranbac.chiptripbe.module.place.service.PlaceEnrichmentService;
import com.tranbac.chiptripbe.module.trip.dto.request.GenerateTripRequest;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
    @Mock private TripGenerationPersistenceService tripGenerationPersistenceService;
    @Mock private Executor enrichmentExecutor;

    @InjectMocks private TripServiceImpl service;

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
}
