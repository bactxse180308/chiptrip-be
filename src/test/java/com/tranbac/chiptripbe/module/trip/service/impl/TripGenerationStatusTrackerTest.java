package com.tranbac.chiptripbe.module.trip.service.impl;

import com.tranbac.chiptripbe.module.trip.dto.response.TripGenerationResultDto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TripGenerationStatusTrackerTest {

    private final TripGenerationStatusTracker tracker = new TripGenerationStatusTracker();

    @Test
    void keepsTerminalResultSoClientCanRecoverAfterMissingWebSocketMessage() {
        tracker.markRunning(42L, "job-1");

        assertThat(tracker.getStatus(42L).status()).isEqualTo("RUNNING");

        tracker.complete(42L, TripGenerationResultDto.done("job-1", 99L, 2));

        var recovered = tracker.getStatus(42L);
        assertThat(recovered.generationId()).isEqualTo("job-1");
        assertThat(recovered.status()).isEqualTo("DONE");
        assertThat(recovered.tripId()).isEqualTo(99L);
        assertThat(recovered.geocodeFailedCount()).isEqualTo(2);
        assertThat(recovered.error()).isNull();
    }

    @Test
    void returnsIdleWhenUserHasNoGenerationJob() {
        assertThat(tracker.getStatus(42L).status()).isEqualTo("IDLE");
    }

    @Test
    void clearsStatusWhenAsyncSubmissionFails() {
        tracker.markRunning(42L, "job-1");

        tracker.clear(42L);

        assertThat(tracker.getStatus(42L).status()).isEqualTo("IDLE");
    }
}
