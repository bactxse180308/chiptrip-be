package com.tranbac.chiptripbe.module.flight.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tranbac.chiptripbe.common.exception.AppException;
import com.tranbac.chiptripbe.module.flight.repository.FlightCacheRepository;
import com.tranbac.chiptripbe.module.geocoding.client.SerpApiClient;
import com.tranbac.chiptripbe.module.trip.entity.Trip;
import com.tranbac.chiptripbe.module.trip.repository.TripRepository;
import com.tranbac.chiptripbe.module.user.service.EntitlementService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Gợi ý vé máy bay là tính năng Premium — chỉ chuyến tạo bởi Premium (gate theo createdAsPremium). */
@ExtendWith(MockitoExtension.class)
class FlightServiceImplTest {

    @Mock private TripRepository tripRepository;
    @Mock private SerpApiClient serpApiClient;
    @Mock private FlightCacheRepository flightCacheRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private EntitlementService entitlementService;

    @InjectMocks private FlightServiceImpl service;

    @Test
    void getFlightSuggestion_normalTripAndNotPremium_throwsPremiumRequiredBeforeSerp() {
        Trip trip = Trip.builder().generatedAsPremium(false).build();
        when(tripRepository.findByIdAndUserId(9L, 1L)).thenReturn(Optional.of(trip));
        when(entitlementService.isPremium(1L)).thenReturn(false);

        AppException ex = assertThrows(AppException.class, () -> service.getFlightSuggestion(1L, 9L));

        assertEquals("PREMIUM_REQUIRED", ex.getCode());
        verifyNoInteractions(serpApiClient, flightCacheRepository);
    }
}
