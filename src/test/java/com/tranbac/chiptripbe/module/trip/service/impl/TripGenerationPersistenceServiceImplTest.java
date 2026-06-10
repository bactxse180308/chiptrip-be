package com.tranbac.chiptripbe.module.trip.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.common.config.AiProperties;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.ai.dto.AiCallResult;
import com.tranbac.chiptripbe.module.ai.dto.AiItineraryResult;
import com.tranbac.chiptripbe.module.ai.repository.AiUsageRepository;
import com.tranbac.chiptripbe.module.notification.event.AiCreditsLowEvent;
import com.tranbac.chiptripbe.module.trip.dto.request.GenerateTripRequest;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.trip.entity.TripDay;
import com.tranbac.chiptripbe.module.trip.repository.ActivityRepository;
import com.tranbac.chiptripbe.module.trip.repository.ChecklistItemRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripDayRepository;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.trip.service.TripMemberService;
import com.tranbac.chiptripbe.module.user.entity.User;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verify Task 1: trừ aiCredits qua atomic UPDATE.
 * - Khi deductCreditIfAvailable trả 0 → throw "Hết lượt AI".
 * - Khi trả 1 và còn > 1 credits → KHÔNG publish AiCreditsLowEvent.
 * - Khi trả 1 và còn ≤ 1 credit → publish AiCreditsLowEvent.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TripGenerationPersistenceServiceImplTest {

    @Mock private TripRepository tripRepository;
    @Mock private TripDayRepository tripDayRepository;
    @Mock private ActivityRepository activityRepository;
    @Mock private ChecklistItemRepository checklistItemRepository;
    @Mock private UserRepository userRepository;
    @Mock private AiUsageRepository aiUsageRepository;
    @Mock private AiProperties aiProperties;
    @Mock private ObjectMapper objectMapper;
    @Mock private TripMemberService tripMemberService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private TripGenerationPersistenceServiceImpl service;

    private static final Long USER_ID = 42L;

    @BeforeEach
    void setup() throws Exception {
        User user = User.builder().email("u@e.com").fullName("U").aiCredits(3).build();
        user.setId(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        when(objectMapper.writeValueAsString(any())).thenReturn("[]");

        when(tripRepository.save(any())).thenAnswer(inv -> {
            Trip t = inv.getArgument(0);
            t.setId(100L);
            return t;
        });
        when(tripDayRepository.save(any())).thenAnswer(inv -> {
            TripDay d = inv.getArgument(0);
            d.setId(200L);
            return d;
        });
        when(activityRepository.save(any())).thenAnswer(inv -> {
            com.tranbac.chiptripbe.module.trip.entity.Activity a = inv.getArgument(0);
            a.setId(300L);
            return a;
        });
        when(checklistItemRepository.save(any())).thenAnswer(inv -> {
            com.tranbac.chiptripbe.module.trip.entity.ChecklistItem c = inv.getArgument(0);
            c.setId(400L);
            return c;
        });

        AiProperties.Pricing pricing = new AiProperties.Pricing();
        when(aiProperties.getPricing()).thenReturn(pricing);
    }

    @Test
    void persistGeneratedTrip_creditExhausted_throwsAppException() {
        when(userRepository.deductCreditIfAvailable(USER_ID)).thenReturn(0);

        AppException ex = assertThrows(AppException.class,
                () -> service.persistGeneratedTrip(USER_ID, request(), aiResult(), Collections.emptyMap()));

        assertTrue(ex.getMessage().contains("Hết lượt AI"),
                "Message phải báo hết lượt: " + ex.getMessage());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void persistGeneratedTrip_creditDeductedRemainingHigh_noLowCreditsEvent() {
        when(userRepository.deductCreditIfAvailable(USER_ID)).thenReturn(1);
        when(userRepository.findAiCreditsById(USER_ID)).thenReturn(5);

        assertDoesNotThrow(() ->
                service.persistGeneratedTrip(USER_ID, request(), aiResult(), Collections.emptyMap()));

        verify(userRepository, times(1)).deductCreditIfAvailable(USER_ID);
        verify(eventPublisher, never()).publishEvent(any(AiCreditsLowEvent.class));
    }

    @Test
    void persistGeneratedTrip_creditDeductedRemainingLow_publishesLowCreditsEvent() {
        when(userRepository.deductCreditIfAvailable(USER_ID)).thenReturn(1);
        when(userRepository.findAiCreditsById(USER_ID)).thenReturn(1);

        service.persistGeneratedTrip(USER_ID, request(), aiResult(), Collections.emptyMap());

        verify(userRepository, times(1)).deductCreditIfAvailable(USER_ID);
        verify(eventPublisher, times(1)).publishEvent(any(AiCreditsLowEvent.class));
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private GenerateTripRequest request() {
        GenerateTripRequest req = org.mockito.Mockito.mock(GenerateTripRequest.class);
        when(req.getStartDate()).thenReturn(LocalDate.of(2026, 7, 1));
        when(req.getEndDate()).thenReturn(LocalDate.of(2026, 7, 1));
        when(req.getDeparture()).thenReturn("Hà Nội");
        when(req.getDestination()).thenReturn("Đà Lạt");
        when(req.getPeopleCount()).thenReturn(2);
        when(req.getBudgetVnd()).thenReturn(5_000_000L);
        when(req.getStyles()).thenReturn(List.of("food_tour"));
        return req;
    }

    private AiCallResult aiResult() {
        AiItineraryResult r = new AiItineraryResult();
        r.setTitle("Đà Lạt 1 ngày");
        AiItineraryResult.AiDay day = new AiItineraryResult.AiDay();
        day.setDayNumber(1);
        day.setDate("2026-07-01");
        AiItineraryResult.AiActivity act = new AiItineraryResult.AiActivity();
        act.setTime("08:00");
        act.setName("Cafe");
        act.setType("FOOD");
        act.setCostVnd(100_000L);
        act.setSearchQuery("Cafe Tùng Đà Lạt");
        day.setActivities(List.of(act));
        r.setDays(List.of(day));
        r.setChecklist(List.of());
        return new AiCallResult(r, 100, 200);
    }
}
