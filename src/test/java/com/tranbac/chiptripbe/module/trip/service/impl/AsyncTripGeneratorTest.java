package com.tranbac.chiptripbe.module.trip.service.impl;

import com.tranbac.chiptripbe.module.trip.dto.request.GenerateTripRequest;
import com.tranbac.chiptripbe.module.trip.dto.response.TripGenerateResponse;
import com.tranbac.chiptripbe.module.trip.service.TripService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AsyncTripGeneratorTest {

    private static final Long USER_ID = 42L;
    private static final String GENERATION_ID = "job-1";

    @Mock private TripService tripService;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private GenerateTripRequest request;
    @Mock private TripGenerateResponse response;

    private TripGenerationLock lock;
    private TripGenerationStatusTracker statusTracker;
    private AsyncTripGenerator generator;

    @BeforeEach
    void setUp() {
        lock = new TripGenerationLock();
        statusTracker = new TripGenerationStatusTracker();
        generator = new AsyncTripGenerator(tripService, messagingTemplate, lock, statusTracker);
    }

    @Test
    void storesDoneStatusBeforeBestEffortWebSocketDelivery() {
        lock.acquire(USER_ID);
        statusTracker.markRunning(USER_ID, GENERATION_ID);
        when(tripService.generateTrip(USER_ID, request)).thenReturn(response);
        when(response.getId()).thenReturn(99L);
        when(response.getGeocodeFailedCount()).thenReturn(2);

        generator.run(USER_ID, GENERATION_ID, request);

        var status = statusTracker.getStatus(USER_ID);
        assertThat(status.generationId()).isEqualTo(GENERATION_ID);
        assertThat(status.status()).isEqualTo("DONE");
        assertThat(status.tripId()).isEqualTo(99L);
        verify(messagingTemplate).convertAndSendToUser(
                org.mockito.ArgumentMatchers.eq(String.valueOf(USER_ID)),
                org.mockito.ArgumentMatchers.eq("/queue/trip-generation"),
                org.mockito.ArgumentMatchers.argThat(result ->
                        result instanceof com.tranbac.chiptripbe.module.trip.dto.response.TripGenerationResultDto dto
                                && "DONE".equals(dto.status())
                                && GENERATION_ID.equals(dto.generationId())
                                && Long.valueOf(99L).equals(dto.tripId())));
    }
}
